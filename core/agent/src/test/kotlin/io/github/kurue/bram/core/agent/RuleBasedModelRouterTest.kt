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

    private fun candidate(id: String, location: ModelLocation) = RoutingCandidate(
        model = ModelDescriptor(
            id = ModelId(id),
            displayName = id,
            providerName = id,
            modelName = id,
            location = location,
            contextWindowTokens = 8_192,
        ),
        available = true,
    )
}
