package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.ModelCapability
import io.github.kurue.bram.core.domain.ModelDescriptor
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelLocation
import io.github.kurue.bram.core.domain.PrivacyClass
import io.github.kurue.bram.core.domain.RoutingCandidate
import io.github.kurue.bram.core.domain.RoutingMode
import io.github.kurue.bram.core.domain.RoutingRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleBasedModelRouterTest {
    @Test
    fun `local-only privacy blocks remote candidate`() {
        val local = candidate("local", ModelLocation.LOCAL)
        val remote = candidate("remote", ModelLocation.REMOTE)

        val decision = RuleBasedModelRouter().route(
            RoutingRequest(
                mode = RoutingMode.AUTO,
                privacyClass = PrivacyClass.LOCAL_ONLY,
                requiredCapabilities = setOf(ModelCapability.TEXT),
                minimumContextTokens = 4_096,
            ),
            listOf(remote, local),
        )

        assertEquals(local.model.id, decision.selected?.model?.id)
        assertEquals("Content may not leave the device", decision.rejectedReasons[remote.model.id])
    }

    @Test
    fun `fallbacks carry the remaining eligible candidates in score order`() {
        val local = candidate("local", ModelLocation.LOCAL)
        val remote = candidate("remote", ModelLocation.REMOTE)
        val other = candidate("other", ModelLocation.REMOTE)

        val decision = RuleBasedModelRouter().route(
            RoutingRequest(
                mode = RoutingMode.AUTO,
                privacyClass = PrivacyClass.STANDARD,
                requiredCapabilities = setOf(ModelCapability.TEXT),
                minimumContextTokens = 4_096,
            ),
            listOf(other, local, remote),
        )

        assertEquals(listOf(other.model.id, remote.model.id), decision.fallbacks.map { it.model.id })
    }

    @Test
    fun `fallbacks exclude candidates that failed a hard gate`() {
        val local = candidate("local", ModelLocation.LOCAL)
        val remote = candidate("remote", ModelLocation.REMOTE)

        val decision = RuleBasedModelRouter().route(
            RoutingRequest(
                mode = RoutingMode.AUTO,
                privacyClass = PrivacyClass.LOCAL_ONLY,
                requiredCapabilities = setOf(ModelCapability.TEXT),
                minimumContextTokens = 4_096,
            ),
            listOf(remote, local),
        )

        // The remote never becomes a fallback: privacy is a hard gate, not a preference.
        assertEquals(emptyList<String>(), decision.fallbacks.map { it.model.id })
    }

    @Test
    fun `local bias lets a prefer-local privacy class outrank a much faster remote`() {
        val local = candidate("local", ModelLocation.LOCAL, quality = 0.3, latency = 5_000)
        val remote = candidate("remote", ModelLocation.REMOTE, quality = 0.9, latency = 100)

        val normal = RuleBasedModelRouter().route(
            request(PrivacyClass.STANDARD),
            listOf(local, remote),
        )
        val preferLocal = RuleBasedModelRouter().route(
            request(PrivacyClass.PRIVATE_REMOTE_ALLOWED),
            listOf(local, remote),
        )

        assertEquals(remote.model.id, normal.selected?.model?.id)
        assertEquals(local.model.id, preferLocal.selected?.model?.id)
    }

    @Test
    fun `remote-only mode rejects local candidates outright`() {
        val local = candidate("local", ModelLocation.LOCAL)
        val remote = candidate("remote", ModelLocation.REMOTE)

        val decision = RuleBasedModelRouter().route(
            RoutingRequest(
                mode = RoutingMode.REMOTE_ONLY,
                privacyClass = PrivacyClass.STANDARD,
                requiredCapabilities = setOf(ModelCapability.TEXT),
                minimumContextTokens = 4_096,
            ),
            listOf(local, remote),
        )

        assertEquals(remote.model.id, decision.selected?.model?.id)
        assertEquals("Remote-only policy", decision.rejectedReasons[local.model.id])
    }

    @Test
    fun `prefer quality lets a small fast local lose to a better remote`() {
        val local = candidate("local", ModelLocation.LOCAL, quality = 0.3, latency = 500)
        val remote = candidate("remote", ModelLocation.REMOTE, quality = 0.9, latency = 2_000)

        val decision = RuleBasedModelRouter().route(
            RoutingRequest(
                mode = RoutingMode.AUTO,
                privacyClass = PrivacyClass.STANDARD,
                requiredCapabilities = setOf(ModelCapability.TEXT),
                minimumContextTokens = 4_096,
                preferQuality = true,
            ),
            listOf(local, remote),
        )

        assertEquals(remote.model.id, decision.selected?.model?.id)
    }

    private fun request(privacyClass: PrivacyClass) = RoutingRequest(
        mode = RoutingMode.AUTO,
        privacyClass = privacyClass,
        requiredCapabilities = setOf(ModelCapability.TEXT),
        minimumContextTokens = 4_096,
        preferQuality = true,
        // Mirrors the app's wiring: a prefer-local privacy class doubles the local bonus.
        localBias = if (privacyClass == PrivacyClass.PRIVATE_REMOTE_ALLOWED) 2.0 else 1.0,
    )

    private fun candidate(
        id: String,
        location: ModelLocation,
        quality: Double? = null,
        latency: Long? = null,
    ) = RoutingCandidate(
        model = ModelDescriptor(
            id = ModelId(id),
            displayName = id,
            providerName = id,
            modelName = id,
            location = location,
            contextWindowTokens = 8_192,
        ),
        available = true,
        estimatedQuality = quality,
        estimatedLatencyMillis = latency,
    )
}
