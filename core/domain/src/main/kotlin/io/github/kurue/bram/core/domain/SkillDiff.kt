package io.github.kurue.bram.core.domain

/** One line of a before/after comparison, with its kind. */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { SAME, ADDED, REMOVED }

    /** The prefix a rendered diff shows in front of the line. */
    val prefix: String
        get() = when (kind) {
            Kind.SAME -> "  "
            Kind.ADDED -> "+ "
            Kind.REMOVED -> "- "
        }
}

/**
 * A line diff for reviewing a skill draft against the version it would replace.
 *
 * Longest-common-subsequence over lines, with the common prefix and suffix trimmed first: a skill
 * edit is usually a few lines in a long body, and trimming turns an O(n·m) table over the whole
 * document into one over the changed middle. When even that middle is enormous (a wholesale
 * rewrite of a very long skill), the diff degrades to "everything removed, everything added"
 * rather than spending memory a phone does not have.
 */
object SkillDiff {
    /** Above this many lines in the changed middle, the LCS table is not worth its memory. */
    const val MAX_LCS_LINES = 1_200

    fun lines(before: String, after: String): List<DiffLine> {
        // An empty side has no lines at all: splitting "" would yield one empty line, which would
        // then read as a removal of nothing in front of a brand-new document.
        val beforeLines = if (before.isEmpty()) emptyList() else before.split('\n')
        val afterLines = if (after.isEmpty()) emptyList() else after.split('\n')

        var start = 0
        while (start < beforeLines.size && start < afterLines.size && beforeLines[start] == afterLines[start]) {
            start++
        }
        var endBefore = beforeLines.size
        var endAfter = afterLines.size
        while (endBefore > start && endAfter > start && beforeLines[endBefore - 1] == afterLines[endAfter - 1]) {
            endBefore--
            endAfter--
        }

        val middleBefore = beforeLines.subList(start, endBefore)
        val middleAfter = afterLines.subList(start, endAfter)
        val result = mutableListOf<DiffLine>()
        for (index in 0 until start) result += DiffLine(DiffLine.Kind.SAME, beforeLines[index])
        if (middleBefore.size > MAX_LCS_LINES || middleAfter.size > MAX_LCS_LINES) {
            middleBefore.forEach { result += DiffLine(DiffLine.Kind.REMOVED, it) }
            middleAfter.forEach { result += DiffLine(DiffLine.Kind.ADDED, it) }
        } else {
            result += lcs(middleBefore, middleAfter)
        }
        for (index in endBefore until beforeLines.size) {
            result += DiffLine(DiffLine.Kind.SAME, beforeLines[index])
        }
        return result
    }

    private fun lcs(a: List<String>, b: List<String>): List<DiffLine> {
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                lengths[i][j] = if (a[i] == b[j]) {
                    lengths[i + 1][j + 1] + 1
                } else {
                    maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
            }
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    out += DiffLine(DiffLine.Kind.SAME, a[i])
                    i++
                    j++
                }
                lengths[i + 1][j] >= lengths[i][j + 1] -> {
                    out += DiffLine(DiffLine.Kind.REMOVED, a[i])
                    i++
                }
                else -> {
                    out += DiffLine(DiffLine.Kind.ADDED, b[j])
                    j++
                }
            }
        }
        while (i < a.size) {
            out += DiffLine(DiffLine.Kind.REMOVED, a[i])
            i++
        }
        while (j < b.size) {
            out += DiffLine(DiffLine.Kind.ADDED, b[j])
            j++
        }
        return out
    }
}
