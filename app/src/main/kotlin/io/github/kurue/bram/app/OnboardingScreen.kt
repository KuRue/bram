package io.github.kurue.bram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kurue.bram.core.domain.FindingLevel
import io.github.kurue.bram.core.domain.ModelAdvice
import io.github.kurue.bram.core.domain.ScanFinding

/**
 * The first-run guide: what Bram is, what this phone can run, and a way to add the first model.
 *
 * Bram has no model downloads of its own, so the guide's job is to say what to look for — the
 * format, the quant, and the size — before handing over to the same import and server flows the
 * Models panel uses. It closes by itself once the first model or server exists.
 */
@Composable
internal fun OnboardingOverlay(
    state: AppUiState,
    onScan: () -> Unit,
    onAddModelFile: () -> Unit,
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
) {
    // The scan is local, quick, and asks for nothing, so it runs as the guide opens instead of
    // waiting behind a welcome page.
    LaunchedEffect(Unit) {
        if (state.hardwareAdvice == null && !state.scanningHardware) onScan()
    }
    // Reopened from Settings with models already present: the guide is done once one more lands.
    val modelCountAtOpen = remember {
        state.localModels.size + state.litertlmModels.size + state.endpoints.size
    }
    val modelCount = state.localModels.size + state.litertlmModels.size + state.endpoints.size
    LaunchedEffect(modelCount) {
        if (modelCount > modelCountAtOpen) onSkip()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            // Swallows taps so the chat underneath cannot be used through the guide. A pointer
            // handler rather than clickable: clickable merges every child into one semantics node,
            // which reads the whole guide to a screen reader as a single button.
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding()
            .padding(16.dp)
            .testTag("onboarding"),
        contentAlignment = Alignment.Center,
    ) {
        // Opaque, unlike the glass panels: the guide sits over a scrim rather than the chat field.
        Box(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Setup(state, onAddModelFile, onConnectServer, onSkip, onRescan = onScan)
            }
        }
    }
}

@Composable
private fun Setup(
    state: AppUiState,
    onAddModelFile: () -> Unit,
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onRescan: () -> Unit,
) {
    val advice = state.hardwareAdvice
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Set up Bram", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Run models on this phone, privately, or use a server you choose.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when {
        state.scanningHardware || (advice == null && state.deviceProfile == null) -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Checking this phone…")
            }
        }
        advice == null -> {
            Text(
                "Bram could not read this phone's hardware. You can still add a model.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRescan) { Text("Try again") }
        }
        else -> {
            Recommendation(advice)
            TextButton(
                onClick = { showDetails = !showDetails },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("onboarding-details"),
            ) { Text(if (showDetails) "Hide details" else "Details") }
            if (showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("onboarding-findings")) {
                    advice.findings.forEach { FindingRow(it) }
                    advice.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    advice.alternatives.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    // What can run here decides the order: a phone that cannot run a local model leads with the
    // server, with the file (for a LiteRT package) second.
    val canRunLocally = advice?.canRunLocally ?: true
    val canImport = advice?.canImportFiles ?: true
    val addFile: @Composable (Boolean) -> Unit = { primary ->
        val modifier = Modifier.fillMaxWidth().testTag("onboarding-add-file")
        if (primary) {
            Button(onClick = onAddModelFile, enabled = !state.isImporting, modifier = modifier) { Text("Add a model file") }
        } else {
            OutlinedButton(onClick = onAddModelFile, enabled = !state.isImporting, modifier = modifier) { Text("Add a model file") }
        }
    }
    val connect: @Composable (Boolean) -> Unit = { primary ->
        val modifier = Modifier.fillMaxWidth().testTag("onboarding-connect-server")
        if (primary) {
            Button(onClick = onConnectServer, modifier = modifier) { Text("Connect a server") }
        } else {
            OutlinedButton(onClick = onConnectServer, modifier = modifier) { Text("Connect a server") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canRunLocally) {
            addFile(true)
            connect(false)
        } else {
            connect(true)
            if (canImport) addFile(false)
        }
        if (canImport) {
            Text(
                "A .gguf or .litertlm file you have downloaded, for example from Hugging Face.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    TextButton(
        onClick = onSkip,
        enabled = !state.isImporting,
        modifier = Modifier.fillMaxWidth().testTag("onboarding-skip"),
    ) { Text("Skip for now") }
}

@Composable
private fun FindingRow(finding: ScanFinding) {
    Row(verticalAlignment = Alignment.Top) {
        val (marker, color) = when (finding.level) {
            FindingLevel.GOOD -> "✓" to MaterialTheme.colorScheme.primary
            FindingLevel.NEUTRAL -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
            FindingLevel.LIMITED -> "!" to MaterialTheme.colorScheme.tertiary
            FindingLevel.BLOCKER -> "×" to MaterialTheme.colorScheme.error
        }
        Text(marker, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(finding.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(finding.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                finding.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Recommendation(advice: ModelAdvice) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .testTag("onboarding-recommendation"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (advice.canRunLocally) "Start with" else "Best option",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(advice.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val examples = (advice.comfortable ?: advice.largest)?.examples.orEmpty()
        if (examples.isNotEmpty()) {
            Text("For example ${examples.take(3).joinToString()}.", style = MaterialTheme.typography.bodyMedium)
        }
        // One line about the phone itself, from the same findings the details expand.
        val phone = advice.findings
            .filter { it.title in setOf("Memory", "Storage", "Processor") }
            .joinToString(" · ") { it.summary }
        if (phone.isNotBlank()) {
            Text(phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
