package io.github.kurue.bram.core.domain

/** The only distinction automatic profile routing needs to expose to the rest of the app. */
enum class RoutingWorkload {
    ROUTINE,
    DEMANDING,
}

/**
 * A conservative first-pass workload classifier.
 *
 * Escalation should be predictable and cheap: running a model merely to decide which model to run
 * would make a short local request feel slow. Several weak signals are therefore required, while
 * one strong signal (substantial code or a long request) is enough on its own.
 */
object RoutingWorkloadClassifier {
    private val demandingTerms = Regex(
        "\\b(architect|architecture|debug|diagnos(?:e|is)|implement|investigate|optimi[sz]e|" +
            "plan|refactor|research|review|trade-?offs?|reason step by step|root cause)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun classify(latestRequest: String, conversationCharacters: Int = latestRequest.length): RoutingWorkload {
        val text = latestRequest.trim()
        if (text.isEmpty()) return RoutingWorkload.ROUTINE

        var score = 0
        if (text.length >= 700) score += 3 else if (text.length >= 260) score += 1
        if (conversationCharacters >= 5_000) score += 1
        if ("```" in text) score += 2
        if (demandingTerms.findAll(text).take(3).count() >= 2) score += 2
        else if (demandingTerms.containsMatchIn(text)) score += 1
        if (Regex("(?m)^\\s*(?:\\d+[.)]|[-*])\\s+").findAll(text).take(4).count() >= 3) score += 1
        if (text.count { it == '?' } >= 3) score += 1

        return if (score >= 3) RoutingWorkload.DEMANDING else RoutingWorkload.ROUTINE
    }
}
