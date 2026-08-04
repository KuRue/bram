package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.ModelLocation
import io.github.kurue.bram.core.domain.PrivacyClass
import io.github.kurue.bram.core.domain.RoutingCandidate
import io.github.kurue.bram.core.domain.RoutingDecision
import io.github.kurue.bram.core.domain.RoutingMode
import io.github.kurue.bram.core.domain.RoutingRequest

class RuleBasedModelRouter {
    fun route(request: RoutingRequest, candidates: List<RoutingCandidate>): RoutingDecision {
        val rejected = linkedMapOf<io.github.kurue.bram.core.domain.ModelId, String>()
        val eligible = candidates.filter { candidate ->
            val reason = rejectionReason(request, candidate)
            if (reason != null) rejected[candidate.model.id] = reason
            reason == null
        }

        val selected = eligible.maxByOrNull { candidate ->
            var score = 0.0
            if (candidate.model.location == ModelLocation.LOCAL) score += 1.0
            if (request.preferQuality) score += (candidate.estimatedQuality ?: 0.0) * 3.0
            candidate.estimatedLatencyMillis?.let { score -= it / 100_000.0 }
            candidate.estimatedBatteryCost?.let { score -= it * 0.25 }
            score
        }

        return RoutingDecision(
            selected = selected,
            rejectedReasons = rejected,
            rationale = buildList {
                if (selected == null) {
                    add("No candidate passed the hard capability and privacy gates.")
                } else {
                    add("Selected ${selected.model.displayName} after hard privacy/capability filtering.")
                    if (selected.model.location == ModelLocation.LOCAL) add("Local execution received the default privacy preference.")
                    if (request.preferQuality) add("Measured or declared quality influenced the score.")
                }
            },
        )
    }

    private fun rejectionReason(request: RoutingRequest, candidate: RoutingCandidate): String? {
        if (!candidate.available) return "Runtime is unavailable"
        if (!candidate.model.capabilities.containsAll(request.requiredCapabilities)) return "Missing required capabilities"
        if (candidate.model.contextWindowTokens < request.minimumContextTokens) return "Context window is too small"
        if (request.mode == RoutingMode.LOCAL_ONLY && candidate.model.location != ModelLocation.LOCAL) return "Local-only policy"
        if (request.mode == RoutingMode.REMOTE_ONLY && candidate.model.location != ModelLocation.REMOTE) return "Remote-only policy"
        if (request.privacyClass == PrivacyClass.LOCAL_ONLY && candidate.model.location == ModelLocation.REMOTE) return "Content may not leave the device"
        return null
    }
}
