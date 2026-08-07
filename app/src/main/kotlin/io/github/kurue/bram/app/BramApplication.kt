package io.github.kurue.bram.app

import android.app.Application
import android.content.Context
import io.github.kurue.bram.core.agent.ContextWindowManager
import io.github.kurue.bram.core.agent.DefaultAgentOrchestrator
import io.github.kurue.bram.core.agent.InMemoryMemoryStore
import io.github.kurue.bram.core.agent.StaticToolRegistry
import io.github.kurue.bram.core.domain.EndpointCredentialResolver
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.kurue.bram.platform.android.AndroidDeviceProfiler
import io.github.kurue.bram.platform.android.ConversationStore
import io.github.kurue.bram.platform.android.SecureEndpointStore
import io.github.kurue.bram.runtime.openai.OpenAiCompatibleRuntime
import io.github.kurue.bram.runtime.llamacpp.LlamaCppRuntime
import io.github.kurue.bram.runtime.llamacpp.LocalModelStore
import io.github.kurue.bram.runtime.llamacpp.ModelProfileStore
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import org.json.JSONArray
import org.json.JSONObject

class BramApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val appContext: Context = application.applicationContext

    /**
     * Outlives any ViewModel so an agent run is not cancelled by the screen going away. Paired with
     * [AgentTaskService], which keeps the process alive for as long as a run holds it.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val endpointStore = SecureEndpointStore(application)
    val localModelStore = LocalModelStore(application)
    val modelProfileStore = ModelProfileStore(application)
    val toolPermissionStore = ToolPermissionStore(application)

    /**
     * Held by the container rather than built per run, so the screen can watch what it is waiting
     * on and a remembered allowance outlives the run that granted it.
     */
    val approvalGate = InteractiveApprovalGate(toolPermissionStore)
    val llamaCppClient = LlamaCppServiceClient(application)
    val deviceProfiler = AndroidDeviceProfiler(application)
    val conversationStore = ConversationStore(application)
    val memoryStore = InMemoryMemoryStore()
    private val toolRegistry = StaticToolRegistry(
        listOf(DeviceStatusTool(deviceProfiler), ScratchNoteTool(application)),
    )

    fun runtime(endpoint: RemoteEndpoint) = OpenAiCompatibleRuntime(
        endpoint = endpoint,
        credentialResolver = EndpointCredentialResolver(endpointStore::resolveCredential),
    )

    fun runtime(model: LocalModelRecord) = LlamaCppRuntime(
        record = model,
        client = llamaCppClient,
    )

    fun agent() = DefaultAgentOrchestrator(
        contextWindowManager = ContextWindowManager(),
        memoryStore = memoryStore,
        toolRegistry = toolRegistry,
        approvalGate = approvalGate,
    )
}

private class DeviceStatusTool(
    private val profiler: AndroidDeviceProfiler,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "device_status",
        description = "Read the phone's current RAM, storage, thermal state, and detected inference accelerators.",
        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        readOnly = true,
    )

    override suspend fun execute(argumentsJson: String): String {
        val profile = profiler.snapshot()
        return JSONObject()
            .put("manufacturer", profile.manufacturer)
            .put("model", profile.model)
            .put("soc", profile.soc)
            .put("available_ram_bytes", profile.availableRamBytes)
            .put("total_ram_bytes", profile.totalRamBytes)
            .put("free_storage_bytes", profile.freeStorageBytes)
            .put("thermal_status", profile.thermalStatus)
            .put(
                "accelerators",
                JSONArray().also { array ->
                    profile.accelerators.forEach { capability ->
                        array.put(
                            JSONObject()
                                .put("kind", capability.kind.name)
                                .put("state", capability.state.name)
                                .put("detail", capability.detail),
                        )
                    }
                },
            )
            .toString()
    }
}
