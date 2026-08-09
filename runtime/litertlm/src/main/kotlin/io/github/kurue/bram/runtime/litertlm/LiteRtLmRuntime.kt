package io.github.kurue.bram.runtime.litertlm

import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RuntimeAvailability
import kotlinx.coroutines.flow.Flow

/**
 * A loaded `.litertlm` package as a routable runtime.
 *
 * The runtime is a thin view over the resident engine: availability says whether the engine is up
 * and holds exactly this package, and generation delegates to it. There is no tokenizer access, so
 * [countTokens] stays null and the context planner falls back to its conservative estimator.
 */
class LiteRtLmRuntime(
    private val record: LiteRtModelRecord,
    private val manager: LiteRtEngineManager,
) : ModelRuntime {
    override val model = record.asModelDescriptor()

    override suspend fun availability(): RuntimeAvailability {
        val engineLoaded = manager.loadedRecordId == record.id.value && manager.isLoaded
        return if (engineLoaded) {
            RuntimeAvailability(
                available = true,
                summary = "${manager.runtimeDescription} ready",
                detail = record.displayName,
            )
        } else {
            RuntimeAvailability(
                available = false,
                summary = "Package is not loaded",
                detail = "Load ${record.displayName} from Models before sending a message.",
            )
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = manager.generate(request)

    override suspend fun cancel(requestId: String) {
        manager.cancel()
    }
}
