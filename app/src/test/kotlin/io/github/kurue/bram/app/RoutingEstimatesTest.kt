package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelCapability
import io.github.kurue.bram.core.domain.ModelLocation
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingEstimatesTest {
    @Test
    fun `only the loaded model is an available local candidate`() {
        val loaded = record("loaded", bytes = 1_000_000_000)
        val idle = record("idle", bytes = 500_000_000)

        val loadedCandidate = RoutingEstimates.localCandidate(loaded, available = true, acceleratorActive = false)
        val idleCandidate = RoutingEstimates.localCandidate(idle, available = false, acceleratorActive = false)

        assertTrue(loadedCandidate.available)
        assertFalse(idleCandidate.available)
        assertEquals(ModelLocation.LOCAL, loadedCandidate.model.location)
    }

    @Test
    fun `a bigger model is estimated smarter and slower`() {
        val small = RoutingEstimates.localCandidate(record("small", bytes = 500_000_000), available = true, acceleratorActive = false)
        val big = RoutingEstimates.localCandidate(record("big", bytes = 4_000_000_000), available = true, acceleratorActive = false)

        assertTrue(big.estimatedQuality!! > small.estimatedQuality!!)
        assertTrue(big.estimatedLatencyMillis!! > small.estimatedLatencyMillis!!)
    }

    @Test
    fun `an active accelerator lowers both latency and battery estimates`() {
        val cpu = RoutingEstimates.localCandidate(record("m", bytes = 1_000_000_000), available = true, acceleratorActive = false)
        val gpu = RoutingEstimates.localCandidate(record("m", bytes = 1_000_000_000), available = true, acceleratorActive = true)

        assertTrue(gpu.estimatedLatencyMillis!! < cpu.estimatedLatencyMillis!!)
        assertTrue(gpu.estimatedBatteryCost!! < cpu.estimatedBatteryCost!!)
    }

    @Test
    fun `both api kinds are routable candidates`() {
        val chat = RoutingEstimates.remoteCandidate(endpoint(RemoteApiKind.CHAT_COMPLETIONS))
        val responses = RoutingEstimates.remoteCandidate(endpoint(RemoteApiKind.RESPONSES))

        assertTrue(chat.available)
        assertTrue(responses.available)
        assertTrue(chat.model.capabilities.contains(ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `remote is assumed frontier quality and cheap battery`() {
        val remote = RoutingEstimates.remoteCandidate(endpoint(RemoteApiKind.CHAT_COMPLETIONS))

        assertTrue(remote.estimatedQuality!! > 0.8)
        assertTrue(remote.estimatedBatteryCost!! < 1.0)
        assertEquals(ModelLocation.REMOTE, remote.model.location)
    }

    private fun record(id: String, bytes: Long) = LocalModelRecord(
        id = ModelId(id),
        displayName = id,
        fileName = "$id.gguf",
        contentUri = "content://test/$id",
        localPath = "/tmp/$id.gguf",
        fileSizeBytes = bytes,
        sha256 = "0".repeat(64),
        ggufVersion = 3,
        architecture = "qwen2",
        quantization = "Q4_K_M",
        trainedContextTokens = 8_192,
        layerCount = 32,
        hasChatTemplate = true,
    )

    private fun endpoint(apiKind: RemoteApiKind) = RemoteEndpoint(
        id = "e1",
        displayName = "Provider",
        baseUrl = "https://example.com/v1",
        modelName = "model-x",
        apiKind = apiKind,
    )
}
