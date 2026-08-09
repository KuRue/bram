package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.ExtractedMemory
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MemoryExtractor
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.SamplerSettings
import io.github.kurue.bram.core.domain.parseExtractedMemories
import java.util.UUID
import kotlinx.coroutines.flow.collect

/**
 * Extracts durable memories by asking the same runtime that just answered the turn to read the
 * exchange back and report what should outlive it.
 *
 * Mirrors the orchestrator's compaction generation: one bounded, tool-free, greedy-decoded call,
 * tolerant of failure (a failed extraction yields nothing rather than taking the run down). The
 * visible reply has already streamed by the time the orchestrator calls this, so the cost is a
 * short generation tacked onto the end of a turn — not latency the user sees before the answer.
 *
 * Only the latest user message and the assistant's reply are offered to the extractor: prior turns
 * are either still in the window (the model already has them) or already summarized into a working
 * summary, so re-reading them here would both bloat the extraction prompt and risk re-extracting
 * what compaction already captured.
 */
class GeneratingMemoryExtractor : MemoryExtractor {
    override suspend fun extract(
        conversationId: ConversationId,
        userMessage: ConversationMessage?,
        assistantReply: String,
        runtime: ModelRuntime,
    ): List<ExtractedMemory> {
        val userText = userMessage?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (assistantReply.isBlank()) return emptyList()

        val output = StringBuilder()
        var failed = false
        runCatching {
            runtime.generate(
                GenerationRequest(
                    messages = listOf(
                        ConversationMessage(role = MessageRole.SYSTEM, content = EXTRACTION_SYSTEM_PROMPT),
                        ConversationMessage(role = MessageRole.USER, content = exchangeFor(userText, assistantReply)),
                    ),
                    tools = emptyList(),
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    sampler = SamplerSettings(temperature = 0f),
                    requestId = "memory-extract-${UUID.randomUUID()}",
                ),
            ).collect { event ->
                when (event) {
                    is GenerationEvent.TextDelta -> output.append(event.text)
                    is GenerationEvent.Failed -> failed = true
                    else -> Unit
                }
            }
        }
        if (failed) return emptyList()
        return parseExtractedMemories(output.toString())
    }

    private fun exchangeFor(userText: String, assistantReply: String): String =
        "User:\n$userText\n\nBram:\n$assistantReply"

    private companion object {
        const val MAX_OUTPUT_TOKENS = 256

        // Kept short and explicit: small local models follow a strict format better than they follow
        // a long instruction, and extraction quality is bounded by the model, not the prompt length.
        const val EXTRACTION_SYSTEM_PROMPT =
            "Read the exchange and pull out only durable memories the assistant should keep for " +
                "future turns. Output ONLY a JSON array, no prose, no code fence. Each element has " +
                "the shape {\"kind\": \"fact\" | \"instruction\", \"text\": string, \"importance\": number}. " +
                "\"fact\": a statement true later (the user's name, tools or languages they use, " +
                "project details, deadlines, preferences about content). " +
                "\"instruction\": a standing directive about how to respond (tone, format, things to " +
                "always or never do). Skip small talk, the current task, and anything that only " +
                "matters right now. If nothing durable, return []."
    }
}
