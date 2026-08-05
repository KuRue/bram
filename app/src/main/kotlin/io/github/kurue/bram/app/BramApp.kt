package io.github.kurue.bram.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurue.bram.core.domain.AcceleratorCapability
import io.github.kurue.bram.core.domain.CapabilityState
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.RemoteEndpoint
import java.util.Locale

private enum class AppSection(val label: String, val glyph: String) {
    CHAT("Chat", "✦"),
    MODELS("Models", "▣"),
    SETTINGS("Settings", "⚙"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BramApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(AppSection.CHAT) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(BramDefaults.IDENTITY.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (section) {
                                AppSection.CHAT -> "Private, local-first chat"
                                AppSection.MODELS -> "GGUF models on this device"
                                AppSection.SETTINGS -> "Providers and diagnostics"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppSection.entries.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Text(item.glyph) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                AppSection.CHAT -> ChatScreen(
                    state = state,
                    onSelectLocal = viewModel::selectLocalModel,
                    onSelectEndpoint = viewModel::selectEndpoint,
                    onSend = viewModel::send,
                    onStop = viewModel::stopGeneration,
                    onClear = viewModel::clearChat,
                    onLoad = viewModel::loadModel,
                    onOpenModels = { section = AppSection.MODELS },
                )
                AppSection.MODELS -> ModelsScreen(
                    state = state,
                    onImport = { modelPicker.launch(arrayOf("*/*")) },
                    onSelect = viewModel::selectLocalModel,
                    onLoad = viewModel::loadModel,
                    onUnload = viewModel::unloadModel,
                    onRemove = viewModel::removeLocalModel,
                    onContext = viewModel::setPreferredContext,
                    onValidateAccelerator = viewModel::validateAccelerator,
                    onBisectAccelerator = viewModel::bisectAccelerator,
                    onReclaimStorage = viewModel::reclaimModelStorage,
                )
                AppSection.SETTINGS -> SettingsScreen(
                    state = state,
                    onSaveEndpoint = viewModel::saveEndpoint,
                    onRemoveEndpoint = viewModel::removeEndpoint,
                    onRefreshDiagnostics = viewModel::refreshDeviceProfile,
                )
            }
        }
    }
}

@Composable
private fun ChatScreen(
    state: AppUiState,
    onSelectLocal: (String) -> Unit,
    onSelectEndpoint: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onLoad: (String) -> Unit,
    onOpenModels: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val hasAnyRuntime = state.localModels.isNotEmpty() || state.endpoints.isNotEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (!hasAnyRuntime) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Run Bram locally", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Import a GGUF model from your phone. No endpoint or account is required.")
                    Button(onClick = onOpenModels) { Text("Import a GGUF") }
                    Text(
                        "Remote OpenAI-compatible providers remain optional under Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            RuntimeSelector(state, onSelectLocal, onSelectEndpoint, onClear)
        }

        state.selectedLocalModel?.takeIf { !state.selectedLocalModelIsLoaded }?.let { model ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${model.displayName} is selected", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Load it in the isolated CPU process before chatting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { onLoad(model.id.value) },
                        enabled = !state.isLoadingModel,
                    ) {
                        if (state.isLoadingModel) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Load")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty() && hasAnyRuntime) {
                item {
                    Text(
                        if (state.selectedLocalModelIsLoaded) "Local model ready. Ask Bram anything."
                        else "Select and load a local model, or choose an optional remote provider.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(state.messages, key = { it.id.value }) { ChatBubble(it) }
            state.status?.let { status ->
                item { Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            state.error?.let { error -> item { ErrorCard(error) } }
        }

        state.lastMetrics?.let { metrics ->
            Text(
                buildString {
                    append("CPU: ${formatRate(metrics.promptTokensPerSecond)} prompt")
                    append(" · ${formatRate(metrics.decodeTokensPerSecond)} generation")
                    metrics.processPssBytes?.let { append(" · ${formatBytes(it)} PSS") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        } ?: state.lastUsage?.let { usage ->
            Text(
                "Last run: ${usage.inputTokens ?: "?"} in · ${usage.outputTokens ?: "?"} out",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message Bram") },
                minLines = 1,
                maxLines = 5,
                enabled = !state.isGenerating,
            )
            Spacer(Modifier.width(8.dp))
            if (state.isGenerating) {
                OutlinedButton(onClick = onStop, modifier = Modifier.height(56.dp)) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && (
                        state.selectedEndpoint != null || state.selectedLocalModelIsLoaded
                    ),
                    modifier = Modifier.height(56.dp),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun RuntimeSelector(
    state: AppUiState,
    onSelectLocal: (String) -> Unit,
    onSelectEndpoint: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.localModels.forEach { model ->
            FilterChip(
                selected = model.id.value == state.selectedRuntimeId,
                onClick = { onSelectLocal(model.id.value) },
                label = {
                    Text(
                        buildString {
                            if (model.id.value == state.loadedModelId) append("● ")
                            append(model.displayName)
                        },
                        maxLines = 1,
                    )
                },
            )
        }
        state.endpoints.forEach { endpoint ->
            FilterChip(
                selected = remoteRuntimeId(endpoint.id) == state.selectedRuntimeId,
                onClick = { onSelectEndpoint(endpoint.id) },
                label = { Text("Cloud · ${endpoint.displayName}", maxLines = 1) },
            )
        }
        TextButton(onClick = onClear, enabled = !state.isGenerating) { Text("Clear") }
    }
}

@Composable
private fun ModelsScreen(
    state: AppUiState,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onLoad: (String) -> Unit,
    onUnload: () -> Unit,
    onRemove: (String) -> Unit,
    onContext: (String, Int) -> Unit,
    onValidateAccelerator: (String, AcceleratorTarget) -> Unit,
    onBisectAccelerator: (String, AcceleratorTarget) -> Unit,
    onReclaimStorage: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Local models", "Verified GGUF files selected from this phone", Modifier.weight(1f))
                Button(onClick = onImport, enabled = !state.isImporting && !state.isGenerating) { Text("Import GGUF") }
            }
        }
        if (state.isImporting) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.importProgress?.stage ?: "Importing model", fontWeight = FontWeight.SemiBold)
                            state.importProgress?.takeIf { it.totalBytes > 0 }?.let {
                                Text(
                                    "${formatBytes(it.bytesRead)} / ${formatBytes(it.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.localModels.isEmpty() && !state.isImporting) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No local models yet", fontWeight = FontWeight.SemiBold)
                        Text("Start with LFM2.5-2.6B-Q4_0.gguf. Bram keeps the file where it is and retains read access.")
                        Button(onClick = onImport) { Text("Choose a GGUF") }
                    }
                }
            }
        }
        items(state.localModels, key = { it.id.value }) { model ->
            LocalModelCard(
                model = model,
                selected = state.selectedRuntimeId == model.id.value,
                loaded = state.loadedModelId == model.id.value && state.cpuValidated,
                loading = state.isLoadingModel && state.selectedRuntimeId == model.id.value,
                generationActive = state.isGenerating,
                onSelect = { onSelect(model.id.value) },
                onLoad = { onLoad(model.id.value) },
                onUnload = onUnload,
                onRemove = { onRemove(model.id.value) },
                onContext = { onContext(model.id.value, it) },
            )
        }
        state.modelLoadDetail?.let { detail -> item { InfoCard("Validated CPU plan", detail) } }
        if (state.modelStorageBytes > 0) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Model storage", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Imported models are copied into Bram's private storage, which currently " +
                                "holds ${formatBytes(state.modelStorageBytes)}. Copies left behind by an " +
                                "interrupted import can be removed safely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onReclaimStorage) { Text("Remove unreferenced copies") }
                    }
                }
            }
        }
        state.selectedLocalModel?.let { model ->
            item {
                AcceleratorValidationCard(
                    state = state,
                    onValidate = { chosen -> onValidateAccelerator(model.id.value, chosen) },
                    onBisect = { chosen -> onBisectAccelerator(model.id.value, chosen) },
                )
            }
        }
        state.error?.let { error -> item { ErrorCard(error) } }
        item {
            Text(
                "Hexagon NPU, LiteRT, downloads, and storage-assisted oversized models remain intentionally disabled in this baseline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AcceleratorValidationCard(
    state: AppUiState,
    onValidate: (AcceleratorTarget) -> Unit,
    onBisect: (AcceleratorTarget) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(AcceleratorTarget.VULKAN) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Accelerator validation", fontWeight = FontWeight.SemiBold)
            Text(
                "Records a deterministic CPU reference, then feeds the same tokens to the accelerator " +
                    "and compares each next-token prediction. Teacher forcing keeps one difference " +
                    "from cascading, so the score reflects compute accuracy rather than drift.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcceleratorTarget.entries.forEach { option ->
                    FilterChip(
                        selected = target == option,
                        onClick = { target = option },
                        label = { Text(option.label) },
                    )
                }
            }
            val busy = state.isValidatingAccelerator || state.isLoadingModel || state.isGenerating
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onValidate(target) }, enabled = !busy) {
                    if (state.isValidatingAccelerator) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isValidatingAccelerator) "Working…" else "Compare GPU against CPU")
                }
                OutlinedButton(onClick = { onBisect(target) }, enabled = !busy) { Text("Bisect layers") }
            }
            state.status?.takeIf { state.isValidatingAccelerator }?.let { status ->
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            state.acceleratorBisection?.let { bisection ->
                HorizontalDivider()
                Text("Layer bisection", fontWeight = FontWeight.SemiBold)
                Text(bisection.detail, style = MaterialTheme.typography.bodySmall)
                bisection.probes.forEach { probe ->
                    Text(
                        "${if (probe.usable) "AGREES" else "DIFFERS"} · ${probe.gpuLayers} layers · " +
                            "%.0f%% · ".format(probe.agreement * 100) + "${probe.millis} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (probe.usable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            state.acceleratorReport?.let { report ->
                HorizontalDivider()
                Text(
                    if (report.matchesCpu) "Validated: output matches CPU" else "Not validated: output diverged",
                    fontWeight = FontWeight.SemiBold,
                    color = if (report.matchesCpu) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(report.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    "CPU ${report.cpuMillis} ms · ${report.deviceName} ${report.acceleratorMillis} ms" +
                        if (report.speedup > 0) " · %.2f× ".format(report.speedup) + "vs CPU" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!report.matchesCpu) {
                    Text(
                        "CPU: ${report.cpuText.take(120)}\nGPU: ${report.acceleratorText.take(120)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalModelCard(
    model: LocalModelRecord,
    selected: Boolean,
    loaded: Boolean,
    loading: Boolean,
    generationActive: Boolean,
    onSelect: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRemove: () -> Unit,
    onContext: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${model.architecture} · ${model.quantization} · ${formatBytes(model.fileSizeBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loaded) Text("✓ CPU ready", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "${model.layerCount.takeIf { it > 0 } ?: "?"} layers · trained context ${formatTokens(model.trainedContextTokens)} · SHA ${model.sha256.take(12)}…",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!model.hasChatTemplate) {
                Text("No chat template found; this model cannot chat until a template override is supported.", color = MaterialTheme.colorScheme.error)
            }
            Text("CPU context", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                contextOptions(model).forEach { tokens ->
                    FilterChip(
                        selected = tokens == model.preferredContextTokens,
                        onClick = { onContext(tokens) },
                        enabled = !loaded && !loading && !generationActive,
                        label = { Text(formatTokens(tokens)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!selected) OutlinedButton(onClick = onSelect) { Text("Select") }
                if (loaded) {
                    OutlinedButton(onClick = onUnload, enabled = !generationActive) { Text("Unload") }
                } else {
                    Button(
                        onClick = onLoad,
                        enabled = !loading && !generationActive && model.hasChatTemplate,
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Load on CPU")
                    }
                }
                TextButton(onClick = onRemove, enabled = !loaded && !loading && !generationActive) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onSaveEndpoint: (EndpointDraft) -> Unit,
    onRemoveEndpoint: (String) -> Unit,
    onRefreshDiagnostics: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:11434/v1") }
    var modelName by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("32768") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var allowHttp by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Optional remote providers", "OpenAI-compatible Chat Completions endpoints")
        if (state.endpoints.isEmpty()) Text("None configured. Local GGUF chat does not require one.")
        state.endpoints.forEach { EndpointCard(it, onRemoveEndpoint) }

        Text("Add provider", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(modelName, { modelName = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            context,
            { context = it.filter(Char::isDigit) },
            label = { Text("Context tokens") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            apiKey,
            { apiKey = it },
            label = { Text("API key (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(allowHttp, { allowHttp = it })
            Column {
                Text("Allow insecure HTTP")
                Text(
                    "Only for a trusted local network; prompts and credentials are unencrypted in transit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = {
                onSaveEndpoint(
                    EndpointDraft(name, baseUrl, modelName, context.toIntOrNull() ?: 0, apiKey, allowHttp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save provider") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Device diagnostics", "Detection is not validation", Modifier.weight(1f))
            TextButton(onClick = onRefreshDiagnostics) { Text("Refresh") }
        }
        state.deviceProfile?.let { profile ->
            DeviceSummaryCard(profile)
            profile.accelerators.forEach { AcceleratorRow(it) }
        } ?: CircularProgressIndicator()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Agent foundation", "Kept behind the local model experience")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadinessRow("Context budgeting", "Working")
                ReadinessRow("Remote tool loop", "Working")
                ReadinessRow("Local isolated inference", if (state.cpuValidated) "CPU validated" else "Awaiting model test")
                ReadinessRow("Durable conversation memory", "Deferred")
                ReadinessRow("Skills and automation", "Deferred")
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage) {
    if (message.role == MessageRole.SYSTEM) return
    val isUser = message.role == MessageRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    when (message.role) {
                        MessageRole.USER -> "You"
                        MessageRole.ASSISTANT -> BramDefaults.IDENTITY.displayName
                        MessageRole.TOOL -> "Tool"
                        MessageRole.SYSTEM -> "System"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(message.content.ifBlank { "…" })
            }
        }
    }
}

@Composable
private fun DeviceSummaryCard(profile: DeviceProfile) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${profile.manufacturer} ${profile.model}", style = MaterialTheme.typography.titleMedium)
            Text(profile.soc, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            MetricRow("RAM available", "${formatBytes(profile.availableRamBytes)} / ${formatBytes(profile.totalRamBytes)}")
            MetricRow("Storage free", "${formatBytes(profile.freeStorageBytes)} / ${formatBytes(profile.totalStorageBytes)}")
            MetricRow("CPU", "${profile.cpuCoreCount} cores · ${profile.appAbi}")
            MetricRow("Thermals", profile.thermalStatus)
        }
    }
}

@Composable
private fun AcceleratorRow(capability: AcceleratorCapability) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            val marker = when (capability.state) {
                CapabilityState.AVAILABLE -> "✓"
                CapabilityState.DETECTED_NOT_VALIDATED -> "?"
                CapabilityState.UNPROBED -> "·"
                CapabilityState.UNAVAILABLE, CapabilityState.FAILED_VALIDATION -> "×"
            }
            Text(marker, modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold)
            Column {
                Text(capability.kind.name.replace('_', ' '), fontWeight = FontWeight.Medium)
                Text(capability.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EndpointCard(endpoint: RemoteEndpoint, onRemove: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(endpoint.displayName, fontWeight = FontWeight.SemiBold)
                Text(endpoint.modelName)
                Text(
                    "${endpoint.baseUrl} · ${formatTokens(endpoint.contextWindowTokens)} context",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onRemove(endpoint.id) }) { Text("Remove") }
        }
    }
}

@Composable
private fun InfoCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorCard(error: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReadinessRow(label: String, status: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

private fun contextOptions(model: LocalModelRecord): List<Int> {
    val maximum = model.trainedContextTokens.takeIf { it > 0 } ?: model.preferredContextTokens
    val standard = listOf(1_024, 2_048, 4_096, 8_192, 16_384, 32_768, 65_536, 131_072)
    return (standard.filter { it <= maximum } + maximum + model.preferredContextTokens)
        .filter { it >= 256 }
        .distinct()
        .sorted()
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes / 1_073_741_824.0
    return if (gib >= 1) String.format(Locale.US, "%.1f GB", gib)
    else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
}

private fun formatTokens(tokens: Int): String = when {
    tokens <= 0 -> "unknown"
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.0fK", tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatRate(rate: Double?): String = rate?.let { String.format(Locale.US, "%.1f tok/s", it) } ?: "measuring"
