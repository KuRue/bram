package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ModelCapability
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.RoutingCandidate

/**
 * Turns real, current state into the router's candidates, with honest estimates where Bram has no
 * measurement yet.
 *
 * The estimates are deliberately simple and monotonic in what they know: quality tracks model
 * size (bigger GGUFs are smarter on the whole), latency tracks size and the active accelerator,
 * battery tracks the accelerator, and a remote provider is assumed frontier-class with a fixed
 * network cost. None of this pretends to be a benchmark — the router just needs relative order.
 */
object RoutingEstimates {

    /**
     * A local GGUF's candidate. [available] is the loaded model only: the llama.cpp service holds
     * exactly one resident model, so any other local record would need a load before it could run.
     */
    fun localCandidate(
        record: LocalModelRecord,
        available: Boolean,
        acceleratorActive: Boolean,
    ): RoutingCandidate {
        val sizeFactor = (record.fileSizeBytes / 2_000_000_000.0).coerceIn(0.0, 1.0)
        val acceleratorFactor = if (acceleratorActive) 0.55 else 1.0
        return RoutingCandidate(
            model = record.asModelDescriptor(),
            available = available,
            estimatedQuality = 0.25 + 0.45 * sizeFactor,
            estimatedLatencyMillis = ((1_000 + record.fileSizeBytes / 10_000_000L) * acceleratorFactor).toLong(),
            estimatedBatteryCost = if (acceleratorActive) 1.5 else 4.0,
        )
    }

    /** A remote endpoint's candidate; configuration is checked by availability(), not routing. */
    fun remoteCandidate(endpoint: RemoteEndpoint): RoutingCandidate = RoutingCandidate(
        model = endpoint.asModelDescriptor(),
        available = true,
        estimatedQuality = 0.9,
        estimatedLatencyMillis = 2_000,
        estimatedBatteryCost = 0.25,
    )

    /**
     * A LiteRT package's candidate. [available] means the resident engine holds exactly this
     * package; a GPU package running on its accelerator is treated like any offloaded model.
     */
    fun localCandidate(
        record: LiteRtModelRecord,
        available: Boolean,
        acceleratorActive: Boolean,
    ): RoutingCandidate {
        val sizeFactor = (record.fileSizeBytes / 2_000_000_000.0).coerceIn(0.0, 1.0)
        val acceleratorFactor = if (acceleratorActive) 0.55 else 1.0
        return RoutingCandidate(
            model = record.asModelDescriptor(),
            available = available,
            estimatedQuality = 0.25 + 0.45 * sizeFactor,
            estimatedLatencyMillis = ((1_000 + record.fileSizeBytes / 10_000_000L) * acceleratorFactor).toLong(),
            estimatedBatteryCost = if (acceleratorActive) 1.5 else 4.0,
        )
    }
}

/** Floor for the routing context check; a model that cannot hold a conversation is not worth routing to. */
internal const val MINIMUM_CONTEXT_TOKENS = 1_024
