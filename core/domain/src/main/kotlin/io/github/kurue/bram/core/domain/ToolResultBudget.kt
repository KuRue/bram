package io.github.kurue.bram.core.domain

/**
 * Bounds one tool result before it enters the transcript.
 *
 * A page, a command's output, or a skill body can be far larger than the context it is about to be
 * replayed into, and nothing downstream can shrink it again: the context manager only trims whole
 * turns as a last resort. The cap tracks the runtime's context window — roughly a quarter of it, in
 * approximate characters — so an 8K-context model cannot be handed a result that alone exceeds the
 * budget, while a large-window model still gets a useful amount.
 */
object ToolResultBudget {
    const val CHARS_PER_TOKEN = 3
    const val MIN_CHARS = 2_000
    const val MAX_CHARS = 64_000

    fun capChars(contextWindowTokens: Int): Int =
        (contextWindowTokens * CHARS_PER_TOKEN / 4).coerceIn(MIN_CHARS, MAX_CHARS)

    /**
     * Returns [result] unchanged when it fits, or a head-and-tail excerpt with a marker naming the
     * original size. The marker is part of the text on purpose: the model must know it is reading
     * an excerpt, and the stored message keeps the same shape as what the model saw.
     */
    fun apply(result: String, contextWindowTokens: Int): String {
        val cap = capChars(contextWindowTokens)
        if (result.length <= cap) return result
        val marker = "\n…[tool result truncated: ${result.length} characters total, $cap kept]…\n"
        if (marker.length >= cap) return result.take(cap)
        val side = (cap - marker.length) / 2
        return result.take(side) + marker + result.takeLast(side)
    }
}
