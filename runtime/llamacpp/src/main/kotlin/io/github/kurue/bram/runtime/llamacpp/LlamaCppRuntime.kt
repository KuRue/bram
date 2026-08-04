package io.github.kurue.bram.runtime.llamacpp

import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RuntimeAvailability
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import kotlinx.coroutines.flow.Flow

class LlamaCppRuntime(
    private val record: LocalModelRecord,
    private val client: LlamaCppServiceClient,
) : ModelRuntime {
    override val model = record.asModelDescriptor()

    override suspend fun availability(): RuntimeAvailability = runCatching {
        val probe = client.probe()
        val state = client.state()
        when {
            !probe.optBoolean("nativeRuntimeLinked") -> RuntimeAvailability(
                available = false,
                summary = "Native CPU runtime unavailable",
                detail = "This APK does not contain a usable llama.cpp library.",
            )
            state.optString("loadedModelId") != record.id.value -> RuntimeAvailability(
                available = false,
                summary = "Model is not loaded",
                detail = "Load ${record.displayName} from Models before sending a message.",
            )
            !state.optBoolean("cpuValidated") -> RuntimeAvailability(
                available = false,
                summary = "CPU plan is not validated",
                detail = "Run the model load/self-test before chatting.",
            )
            else -> RuntimeAvailability(
                available = true,
                summary = "Local CPU ready",
                detail = "llama.cpp ${probe.optString("llamaCppCommit").take(12)}",
            )
        }
    }.getOrElse { error ->
        RuntimeAvailability(
            available = false,
            summary = "Inference process unavailable",
            detail = error.message ?: error::class.java.simpleName,
        )
    }

    override suspend fun countTokens(messages: List<ConversationMessage>): Int? = client.countTokens(messages)

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = client.generate(request)

    override suspend fun cancel(requestId: String) = client.cancel(requestId)
}
