package io.github.kurue.bram.app

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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.RemoteEndpoint
import java.util.Locale

private enum class AppSection(val label: String, val glyph: String) {
    HOME("Home", "●"),
    CHAT("Chat", "✦"),
    PROVIDERS("Models", "+"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BramApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(AppSection.HOME) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(BramDefaults.IDENTITY.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (section) {
                                AppSection.HOME -> "Hardware-aware inference"
                                AppSection.CHAT -> "Local or compatible remote model"
                                AppSection.PROVIDERS -> "Model endpoints"
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (section) {
                AppSection.HOME -> DashboardScreen(state, viewModel::refreshDeviceProfile)
                AppSection.CHAT -> ChatScreen(
                    state = state,
                    onSelectEndpoint = viewModel::selectEndpoint,
                    onSend = viewModel::send,
                    onClear = viewModel::clearChat,
                    onOpenProviders = { section = AppSection.PROVIDERS },
                )
                AppSection.PROVIDERS -> ProviderScreen(
                    state = state,
                    onSave = viewModel::saveEndpoint,
                    onRemove = viewModel::removeEndpoint,
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(state: AppUiState, onRefresh: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader("This device", "Measured now; backend kernels still require native self-tests")
        }
        val profile = state.deviceProfile
        if (profile == null) {
            item { CircularProgressIndicator() }
        } else {
            item { DeviceSummaryCard(profile, onRefresh) }
            items(profile.accelerators) { AcceleratorRow(it) }
        }
        item {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Agent foundation", "The contracts are wired; durable implementations arrive in stages")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ReadinessRow("Context budgeting", "Working")
                    ReadinessRow("OpenAI-compatible runtime", "Working")
                    ReadinessRow("Tool-call loop + approvals", "Working")
                    ReadinessRow("Out-of-process inference", "Protocol ready")
                    ReadinessRow("Durable memory + skills", "Next phase")
                    ReadinessRow("Automation execution", "Next phase")
                }
            }
        }
    }
}

@Composable
private fun DeviceSummaryCard(profile: DeviceProfile, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${profile.manufacturer} ${profile.model}", style = MaterialTheme.typography.titleMedium)
                    Text(profile.soc, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            HorizontalDivider()
            MetricRow("RAM available", "${formatBytes(profile.availableRamBytes)} / ${formatBytes(profile.totalRamBytes)}")
            MetricRow("App storage free", "${formatBytes(profile.freeStorageBytes)} / ${formatBytes(profile.totalStorageBytes)}")
            MetricRow("CPU", "${profile.cpuCoreCount} cores · ${profile.appAbi}")
            MetricRow("Thermals", profile.thermalStatus)
            MetricRow("Profile", profile.profileFingerprint)
        }
    }
}

@Composable
private fun AcceleratorRow(capability: AcceleratorCapability) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
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
private fun ChatScreen(
    state: AppUiState,
    onSelectEndpoint: (String) -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        if (state.endpoints.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("No runtime configured", fontWeight = FontWeight.SemiBold)
                    Text("Add an OpenAI-compatible endpoint now. Local GGUF appears here once the native runtime is integrated.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenProviders) { Text("Add model endpoint") }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.endpoints.forEach { endpoint ->
                    FilterChip(
                        selected = endpoint.id == state.selectedEndpointId,
                        onClick = { onSelectEndpoint(endpoint.id) },
                        label = { Text(endpoint.displayName, maxLines = 1) },
                    )
                }
                TextButton(onClick = onClear, enabled = !state.isGenerating) { Text("Clear") }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id.value }) { message -> ChatBubble(message) }
            state.status?.let { status ->
                item {
                    Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            state.error?.let { error ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        state.lastUsage?.let { usage ->
            Text(
                "Last run: ${usage.inputTokens ?: "?"} in · ${usage.outputTokens ?: "?"} out",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                minLines = 1,
                maxLines = 5,
                enabled = !state.isGenerating,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !state.isGenerating && state.endpoints.isNotEmpty(),
                modifier = Modifier.height(56.dp),
            ) {
                if (state.isGenerating) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage) {
    if (message.role == MessageRole.SYSTEM) return
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
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
private fun ProviderScreen(
    state: AppUiState,
    onSave: (EndpointDraft) -> Unit,
    onRemove: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:11434/v1") }
    var modelName by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("32768") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var allowHttp by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Configured endpoints", "Chat Completions-compatible servers")
        if (state.endpoints.isEmpty()) {
            Text("None yet. Add Ollama, LM Studio, vLLM, llama.cpp server, or a hosted provider below.")
        }
        state.endpoints.forEach { endpoint -> EndpointCard(endpoint, onRemove) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Add endpoint", "Base URL should normally end in /v1")
        OutlinedTextField(name, { name = it }, label = { Text("Name (for example, Home Ollama)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(modelName, { modelName = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(context, { context = it.filter(Char::isDigit) }, label = { Text("Context window tokens") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            apiKey,
            { apiKey = it },
            label = { Text("API key (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allowHttp, onCheckedChange = { allowHttp = it })
            Column {
                Text("Allow insecure HTTP")
                Text(
                    "Only for a trusted local network. Credentials and prompts are not encrypted in transit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = {
                onSave(
                    EndpointDraft(
                        displayName = name,
                        baseUrl = baseUrl,
                        modelName = modelName,
                        contextWindowTokens = context.toIntOrNull() ?: 0,
                        apiKey = apiKey,
                        allowInsecureHttp = allowHttp,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save endpoint")
        }
        state.error?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EndpointCard(endpoint: RemoteEndpoint, onRemove: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
private fun SectionHeader(title: String, subtitle: String) {
    Column {
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

private fun formatBytes(bytes: Long): String {
    val gib = bytes / 1_073_741_824.0
    return if (gib >= 1) String.format(Locale.US, "%.1f GB", gib) else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.0fK", tokens / 1_000.0)
    else -> tokens.toString()
}
