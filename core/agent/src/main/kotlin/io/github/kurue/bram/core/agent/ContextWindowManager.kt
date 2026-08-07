package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.MemoryRecord
import io.github.kurue.bram.core.domain.MessageId
import io.github.kurue.bram.core.domain.MessageRole
import kotlin.math.ceil
import kotlin.math.max

fun interface TokenEstimator {
    suspend fun estimate(messages: List<ConversationMessage>): Int
}

class HeuristicTokenEstimator : TokenEstimator {
    override suspend fun estimate(messages: List<ConversationMessage>): Int = messages.sumOf { message ->
        // Deliberately conservative for mixed prose/code until a runtime tokenizer is connected.
        max(1, ceil(message.content.length / 3.5).toInt()) + 6 +
            message.toolCalls.sumOf { ceil((it.name.length + it.argumentsJson.length) / 3.5).toInt() + 8 }
    }
}

data class ContextPlan(
    val messages: List<ConversationMessage>,
    val estimatedInputTokens: Int,
    val inputBudgetTokens: Int,
    val reservedOutputTokens: Int,
    val omittedMessageIds: List<MessageId>,
    val truncatedMessageIds: List<MessageId>,
    val includedMemoryIds: List<String>,
)

class ContextWindowManager(
    private val fallbackEstimator: TokenEstimator = HeuristicTokenEstimator(),
) {
    suspend fun plan(
        systemPrompt: String,
        /**
         * The profile's own instructions, if it has any.
         *
         * Added after the harness prompt rather than replacing it. The harness prompt is where the
         * rules that keep a run honest live — tool output is untrusted, nothing is reported as
         * having succeeded without evidence — and a persona should not be able to drop them by
         * being written in the same box.
         */
        profileInstructions: String = "",
        transcript: List<ConversationMessage>,
        contextWindowTokens: Int,
        requestedOutputTokens: Int,
        workingSummary: MemoryRecord?,
        retrievedMemories: List<MemoryRecord>,
        runtimeTokenCount: suspend (List<ConversationMessage>) -> Int? = { null },
    ): ContextPlan {
        require(contextWindowTokens >= 256) { "Context window must be at least 256 tokens" }

        val reserve = requestedOutputTokens.coerceIn(64, contextWindowTokens / 2)
        val inputBudget = contextWindowTokens - reserve
        val estimator = TokenEstimator { messages ->
            runtimeTokenCount(messages) ?: fallbackEstimator.estimate(messages)
        }

        val fixed = mutableListOf(
            ConversationMessage(role = MessageRole.SYSTEM, content = systemPrompt.trim()),
        )
        profileInstructions.trim().takeIf(String::isNotEmpty)?.let { instructions ->
            fixed += ConversationMessage(role = MessageRole.SYSTEM, content = instructions)
        }
        val includedMemoryIds = mutableListOf<String>()

        workingSummary?.let {
            fixed += ConversationMessage(
                role = MessageRole.SYSTEM,
                content = "Working conversation summary (derived; transcript remains canonical):\n${it.text}",
            )
            includedMemoryIds += it.id
        }

        for (memory in retrievedMemories.sortedByDescending { it.importance }) {
            val candidate = fixed + ConversationMessage(
                role = MessageRole.SYSTEM,
                content = "Relevant ${memory.kind.name.lowercase().replace('_', ' ')}:\n${memory.text}",
            )
            if (estimator.estimate(candidate) <= inputBudget * 0.45) {
                fixed += candidate.last()
                includedMemoryIds += memory.id
            }
        }

        val selected = mutableListOf<ConversationMessage>()
        var estimated = estimator.estimate(fixed)
        val nonSystemTranscript = transcript.filterNot { it.role == MessageRole.SYSTEM }
        val groups = coherentTurnGroups(nonSystemTranscript)

        for (group in groups.asReversed()) {
            val groupTokens = estimator.estimate(group)
            if (estimated + groupTokens <= inputBudget) {
                selected.addAll(0, group)
                estimated += groupTokens
            } else {
                // Context is a contiguous recent suffix. Do not skip a large recent turn just to
                // admit less-relevant older turns that happen to be shorter.
                break
            }
        }

        val truncated = mutableListOf<MessageId>()
        if (selected.isEmpty() && nonSystemTranscript.isNotEmpty()) {
            val newestGroup = groups.lastOrNull().orEmpty()
            val remainingTokens = (inputBudget - estimated - newestGroup.size * 8).coerceAtLeast(16)
            val tokensPerMessage = (remainingTokens / newestGroup.size.coerceAtLeast(1)).coerceAtLeast(8)
            newestGroup.forEach { message ->
                val replacement = message.copy(content = truncateToApproxTokens(message.content, tokensPerMessage))
                selected += replacement
                if (replacement.content != message.content) truncated += message.id
            }
        }

        val outputMessages = fixed + selected
        val includedIds = selected.mapTo(hashSetOf()) { it.id }
        val omittedIds = nonSystemTranscript.mapNotNull { message ->
            message.id.takeUnless { it in includedIds }
        }

        return ContextPlan(
            messages = outputMessages,
            estimatedInputTokens = estimator.estimate(outputMessages),
            inputBudgetTokens = inputBudget,
            reservedOutputTokens = reserve,
            omittedMessageIds = omittedIds,
            truncatedMessageIds = truncated,
            includedMemoryIds = includedMemoryIds,
        )
    }

    private fun coherentTurnGroups(messages: List<ConversationMessage>): List<List<ConversationMessage>> {
        if (messages.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<ConversationMessage>>()
        for (message in messages) {
            if (groups.isEmpty() || message.role == MessageRole.USER) {
                groups += mutableListOf(message)
            } else {
                groups.last() += message
            }
        }
        return groups
    }

    private fun truncateToApproxTokens(text: String, tokens: Int): String {
        val targetChars = (tokens * 3).coerceAtLeast(48)
        if (text.length <= targetChars) return text
        val marker = "\n…[middle truncated to fit context]…\n"
        val side = ((targetChars - marker.length) / 2).coerceAtLeast(16)
        return text.take(side) + marker + text.takeLast(side)
    }
}
