package io.github.kurue.bram.runtime.llamacpp

import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.ModelDescriptor
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RuntimeAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stable Kotlin boundary for the future AIDL/JNI implementation.
 *
 * Do not load a native library in this class's initializer. Backend/driver crashes must remain in
 * the :inference process, and simply browsing the model catalog must never initialize a driver.
 */
class LlamaCppRuntime(
    override val model: ModelDescriptor,
) : ModelRuntime {
    override suspend fun availability(): RuntimeAvailability = RuntimeAvailability(
        available = false,
        summary = "Native runtime not linked",
        detail = "Vendor a pinned llama.cpp build and connect this adapter to IInferenceService.",
    )

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(
            GenerationEvent.Failed(
                message = "llama.cpp integration is scaffolded but not implemented",
                recoverable = true,
            ),
        )
    }
}
