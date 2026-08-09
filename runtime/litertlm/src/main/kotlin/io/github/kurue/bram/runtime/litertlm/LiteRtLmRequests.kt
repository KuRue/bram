package io.github.kurue.bram.runtime.litertlm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import com.google.ai.edge.litertlm.tool
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.SamplerSettings
import io.github.kurue.bram.core.domain.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps Bram's conversation, tools, and sampler onto the LiteRT-LM API.
 *
 * A LiteRT-LM conversation is created once with a fixed system instruction and an initial history,
 * and a turn sends one message on top of it. Bram's runtime instead creates a fresh conversation
 * for every `generate` call and replays the planned messages, which keeps the runtime stateless the
 * way the [io.github.kurue.bram.core.domain.ModelRuntime] contract expects: a failed attempt can
 * never leave a half-sent conversation behind, and cancellation does not have to roll native state
 * back. The planner below does that replay, mirroring exactly the shapes the library itself builds
 * when a turn runs end to end (assistant tool-call messages and tool responses included), so the
 * engine sees a history identical to one that had been sent turn by turn.
 *
 * Pure Kotlin and JSON: nothing here touches native code, so the mapping is unit-testable.
 */
object LiteRtLmRequests {

    /**
     * Everything a conversation needs: the system instruction the engine prepends, the history to
     * replay (every message except the last), and the message the turn actually sends.
     *
     * Returns null when there is no message to send.
     */
    fun planConversation(messages: List<ConversationMessage>): ConversationPlan? {
        if (messages.isEmpty()) return null

        // The planner's head may carry several SYSTEM messages (identity, then profile
        // instructions). A conversation's system instruction is fixed at creation, so they fold
        // into one. A system message that appears later (not produced by Bram's planner, but
        // tolerated) is demoted to a user message rather than dropped.
        val leadingSystem = messages.takeWhile { it.role == MessageRole.SYSTEM }
        val systemInstruction = leadingSystem
            .joinToString("\n\n") { it.content.trim() }
            .trim()
            .takeIf(String::isNotEmpty)

        // Assistant tool calls need their tool name back when the result message arrives; the
        // library matches a tool response by name, and Bram's transcript only records the call id.
        val callNames = mutableMapOf<String, String>()
        val replay = mutableListOf<Message>()
        for (message in messages) {
            // The leading system messages are already folded into the system instruction, which
            // the engine prepends to the initial history; repeating them here would double them.
            if (message.role == MessageRole.SYSTEM && replay.isEmpty() && systemInstruction != null) continue
            val mapped = message.toLiteRtMessage(callNames) ?: continue
            replay += mapped
        }
        val last = replay.removeLastOrNull() ?: return null
        return ConversationPlan(
            systemInstruction = systemInstruction?.let { Contents.of(it) },
            replay = replay,
            lastMessage = last,
        )
    }

    /** Maps one planned message onto the library shape, learning call ids as it goes. */
    private fun ConversationMessage.toLiteRtMessage(callNames: MutableMap<String, String>): Message? =
        when (role) {
            MessageRole.SYSTEM -> Message.user(content)
            MessageRole.USER -> Message.user(content)
            MessageRole.ASSISTANT -> {
                if (toolCalls.isEmpty()) {
                    Message.model(content)
                } else {
                    val calls = toolCalls.map { call ->
                        callNames[call.id] = call.name
                        LiteRtToolCall(call.name, argumentsToMap(call.argumentsJson))
                    }
                    // Text and calls can coexist in one message; the library's own parse produces
                    // exactly that shape. An assistant turn that only called tools has no text, so
                    // its contents stay empty, mirroring what the library returns for such a turn.
                    val contents = content.takeIf(String::isNotBlank)
                        ?.let { Contents.of(it) }
                        ?: Contents.of("")
                    Message.model(contents = contents, toolCalls = calls)
                }
            }
            MessageRole.TOOL -> {
                val name = toolCallId?.let { callNames[it] } ?: return null
                Message.tool(
                    Contents.of(listOf(Content.ToolResponse(name, toolResponseValue(content)))),
                )
            }
        }

    private fun argumentsToMap(argumentsJson: String): Map<String, Any?> = runCatching {
        jsonToAny(JSONObject(argumentsJson)) as Map<String, Any?>
    }.getOrDefault(emptyMap())

    /** The sampler LiteRT-LM understands, clamped to the ranges its config requires. */
    fun samplerConfig(sampler: SamplerSettings): SamplerConfig = SamplerConfig(
        topK = sampler.topK.coerceAtLeast(1),
        topP = sampler.topP.toDouble().coerceIn(0.0, 1.0),
        temperature = sampler.temperature.toDouble().coerceAtLeast(0.0),
    )

    /**
     * The repetition penalty as a per-turn config, or null when Bram's setting is below the
     * penalty LiteRT-LM supports (it clamps anything under 1.0 to no penalty anyway, and 1.0
     * exactly is no penalty, so only a setting that actually raises it is worth sending).
     */
    fun repetitionPenalty(sampler: SamplerSettings): RepetitionPenaltyConfig? =
        sampler.repeatPenalty.takeIf { it > 1.0f }?.let { RepetitionPenaltyConfig(repetitionPenalty = it) }

    /** Wraps Bram's tool definitions as the OpenAPI tools the engine lists in the prompt. */
    fun toolProviders(tools: List<ToolDefinition>): List<com.google.ai.edge.litertlm.ToolProvider> =
        tools.map { definition -> tool(BramOpenApiTool(definition)) }

    /**
     * The OpenAPI description of one tool, exactly as the engine sees it: the parameters schema
     * Bram already carries embedded under `parameters`. Exposed for tests.
     */
    fun toolDescriptionJson(tool: ToolDefinition): String = JSONObject()
        .put("name", tool.name)
        .put("description", tool.description)
        .apply {
            val parameters = runCatching { JSONObject(tool.inputSchemaJson) }.getOrNull()
            if (parameters != null && parameters.length() > 0) put("parameters", parameters)
        }
        .toString()

    /** The model's call arguments (a JSON object) become the JSON string the agent loop expects. */
    fun toolCallArgumentsJson(call: LiteRtToolCall): String = toJson(call.arguments).toString()

    /**
     * A tool result string becomes the structured value the library embeds in the tool-response
     * content. A string that is not JSON stays a plain string, which is the honest shape for
     * prose-shaped tool output.
     */
    fun toolResponseValue(resultJson: String): Any? {
        val trimmed = resultJson.trim()
        if (trimmed.isEmpty()) return resultJson
        return runCatching {
            when {
                trimmed.startsWith("{") -> jsonToAny(JSONObject(trimmed))
                trimmed.startsWith("[") -> jsonToAny(JSONArray(trimmed))
                else -> null
            }
        }.getOrNull() ?: resultJson
    }

    /** org.json → plain Kotlin structures, so values cross into the library's Gson-based JSON. */
    private fun jsonToAny(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val map = linkedMapOf<String, Any?>()
            for (key in value.keys()) map[key] = jsonToAny(value.get(key))
            map
        }
        is JSONArray -> List(value.length()) { index -> jsonToAny(value.get(index)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { json ->
            value.forEach { (key, element) -> json.put(key.toString(), toJson(element)) }
        }
        is List<*> -> JSONArray().also { array -> value.forEach { array.put(toJson(it)) } }
        is Int, is Long, is Boolean, is Double, is Float -> value
        else -> value.toString()
    }

    data class ConversationPlan(
        val systemInstruction: Contents?,
        val replay: List<Message>,
        val lastMessage: Message,
    )

    private class BramOpenApiTool(
        private val definition: ToolDefinition,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = toolDescriptionJson(definition)

        override fun execute(paramsJsonString: String): String =
            throw UnsupportedOperationException(
                "Tool execution is handled by Bram's agent loop, not the LiteRT-LM runtime",
            )
    }
}

/**
 * The pieces of a [GenerationRequest] a turn needs, assembled by [LiteRtLmRequests.planConversation]
 * and consumed by the engine manager. Kept as a type so the generate path stays one call.
 */
data class LiteRtTurn(
    val systemInstruction: Contents?,
    val replay: List<Message>,
    val lastMessage: Message,
    val maxOutputTokens: Int,
    val sampler: SamplerConfig,
    val repetitionPenalty: RepetitionPenaltyConfig?,
    val tools: List<com.google.ai.edge.litertlm.ToolProvider>,
) {
    companion object {
        fun from(request: GenerationRequest): LiteRtTurn? {
            val plan = LiteRtLmRequests.planConversation(request.messages) ?: return null
            return LiteRtTurn(
                systemInstruction = plan.systemInstruction,
                replay = plan.replay,
                lastMessage = plan.lastMessage,
                maxOutputTokens = request.maxOutputTokens,
                sampler = LiteRtLmRequests.samplerConfig(request.sampler),
                repetitionPenalty = LiteRtLmRequests.repetitionPenalty(request.sampler),
                tools = LiteRtLmRequests.toolProviders(request.tools),
            )
        }
    }
}
