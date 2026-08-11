package io.github.kurue.bram.app

import android.app.Application
import android.content.Context
import io.github.kurue.bram.core.agent.ContextWindowManager
import io.github.kurue.bram.core.agent.DefaultAgentOrchestrator
import io.github.kurue.bram.core.agent.GeneratingMemoryExtractor
import io.github.kurue.bram.core.agent.MutableToolRegistry
import io.github.kurue.bram.core.agent.StaticToolRegistry
import io.github.kurue.bram.core.domain.EndpointCredentialResolver
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.kurue.bram.platform.android.AndroidDeviceProfiler
import io.github.kurue.bram.platform.android.ConversationStore
import io.github.kurue.bram.platform.android.McpServerStore
import io.github.kurue.bram.platform.android.PersistentAutomationStore
import io.github.kurue.bram.platform.android.PersistentMemoryStore
import io.github.kurue.bram.platform.android.PersistentSkillStore
import io.github.kurue.bram.platform.android.RoutingSettingsStore
import io.github.kurue.bram.platform.android.SecureEndpointStore
import io.github.kurue.bram.platform.android.SqliteRunJournal
import io.github.kurue.bram.runtime.openai.OpenAiCompatibleRuntime
import io.github.kurue.bram.runtime.llamacpp.LlamaCppRuntime
import io.github.kurue.bram.runtime.llamacpp.LocalModelStore
import io.github.kurue.bram.runtime.llamacpp.ModelProfileStore
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppEmbedder
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import io.github.kurue.bram.runtime.litertlm.LiteRtEngineManager
import io.github.kurue.bram.runtime.litertlm.LiteRtLmRuntime
import io.github.kurue.bram.runtime.litertlm.LiteRtLmStore
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
    val mcpServerStore = McpServerStore(application)
    val skillStore = PersistentSkillStore(application)
    val localModelStore = LocalModelStore(application)
    val modelProfileStore = ModelProfileStore(application)
    /** LiteRT packages (`.litertlm` files) are tracked like GGUFs but in their own catalog. */
    val liteRtLmStore = LiteRtLmStore(application)
    /**
     * The one LiteRT-LM engine the app keeps resident, shared by every dialog's LiteRT route the
     * way the llama.cpp process serves every GGUF route. Loads and unloads through the Models
     * screen; the engine itself lives in this process.
     */
    val liteRtEngineManager = LiteRtEngineManager(application)
    val toolPermissionStore = ToolPermissionStore(application)
    val notificationSettings = NotificationSettingsStore(application)
    val routingSettings = RoutingSettingsStore(application)

    /**
     * Held by the container rather than built per run, so the screen can watch what it is waiting
     * on and a remembered allowance outlives the run that granted it. Wrapped so an allowed call
     * that still needs a runtime permission asks for it through the system dialog before running.
     */
    val runtimePermissionBroker = RuntimePermissionBroker(application)
    val approvalGate = PermissionAwareApprovalGate(
        InteractiveApprovalGate(toolPermissionStore),
        runtimePermissionBroker,
    )
    val taskStore = AgentTaskStore(application)
    val taskRunner = AgentTaskRunner(application, appScope, taskStore)
    val automationStore = PersistentAutomationStore(application)
    val automationRunner = AutomationRunner(application, appScope, automationStore, taskRunner)
    val embeddingModelStore = EmbeddingModelStore(application)
    val llamaCppClient = LlamaCppServiceClient(application)
    val deviceProfiler = AndroidDeviceProfiler(application)
    val conversationStore = ConversationStore(application)
    val memoryStore = PersistentMemoryStore(
        application,
        // Skips the IPC entirely when no embedding model is designated, so the common keyword-only
        // case pays nothing and the inference process is not bound on every memory write.
        object : io.github.kurue.bram.core.domain.Embedder {
            override suspend fun embed(text: String): FloatArray? {
                if (embeddingModelStore.modelId() == null) return null
                return LlamaCppEmbedder(llamaCppClient).embed(text)
            }
        },
    )
    val runJournal = SqliteRunJournal(application)
    /**
     * Built-ins plus whatever MCP servers contribute. Mutable so a server's tools can be swapped in
     * on refresh and dropped when it fails or is removed; the agent reads it per run.
     */
    val toolRegistry = MutableToolRegistry(
        StaticToolRegistry(
            listOf(
                DeviceStatusTool(deviceProfiler),
                ScratchNoteTool(application),
                WebSearchTool(),
                WebFetchTool(),
                FilesTool(application),
                ListFilesTool(application),
                ReadFileTool(application),
                ClipboardTool(application),
                ClipboardSetTool(application),
                NotificationTool(application),
                ScheduleNotificationTool(application),
                LaunchUriTool(application),
                ContactsTool(application),
                CalendarTool(application),
                TermuxCommandTool(application),
                MemorySearchTool(memoryStore),
                ProposeSkillTool(skillStore),
            ),
        ),
    )

    fun runtime(endpoint: RemoteEndpoint) = OpenAiCompatibleRuntime(
        endpoint = endpoint,
        credentialResolver = EndpointCredentialResolver(endpointStore::resolveCredential),
    )

    fun runtime(model: LocalModelRecord) = LlamaCppRuntime(
        record = model,
        client = llamaCppClient,
    )

    fun runtime(record: LiteRtModelRecord) = LiteRtLmRuntime(
        record = record,
        manager = liteRtEngineManager,
    )

    fun agent() = DefaultAgentOrchestrator(
        contextWindowManager = ContextWindowManager(),
        memoryStore = memoryStore,
        toolRegistry = toolRegistry,
        approvalGate = approvalGate,
        journal = runJournal,
        memoryExtractor = GeneratingMemoryExtractor(),
    )

    init {
        // Load the designated embedding model into the inference process so recall can cosine-rank
        // from the first turn. Best-effort: a missing file or a changed path just leaves recall on
        // keyword (FTS) mode until the user picks a model again.
        appScope.launch {
            val path = embeddingModelStore.modelPath()
            if (!path.isNullOrBlank()) {
                runCatching { llamaCppClient.loadEmbedder(path, EMBEDDER_THREADS) }
            }
        }
    }

    private companion object {
        const val EMBEDDER_THREADS = 4
    }
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
