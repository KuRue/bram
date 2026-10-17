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
    var started by rememberSaveable { mutableStateOf(state.hardwareAdvice != null) }
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
        // Opaque, unlike the glass panels: the guide sits over a scrim rather than the chat field,
        // and glass over a scrim left dark theme text on a dark card.
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
                if (!started) {
                    Introduction(
                        onScan = {
                            started = true
                            onScan()
                        },
                        onSkip = onSkip,
                    )
                } else {
                    ScanResults(state, onAddModelFile, onConnectServer, onSkip, onRescan = onScan)
                }
            }
        }
    }
}

@Composable
private fun Introduction(onScan: () -> Unit, onSkip: () -> Unit) {
    Text("Welcome to Bram", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "Bram runs AI models on this phone, privately. It can also use a model on a server you trust.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "To start, Bram checks what this phone can run (memory, processor, NPU, and GPU) and " +
            "suggests the kind of model to add. Nothing is downloaded or sent anywhere.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onScan, modifier = Modifier.fillMaxWidth().testTag("onboarding-scan")) {
        Text("Check this phone")
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().testTag("onboarding-skip")) {
        Text("Skip for now")
    }
}

@Composable
private fun ScanResults(
    state: AppUiState,
    onAddModelFile: () -> Unit,
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onRescan: () -> Unit,
) {
    val advice = state.hardwareAdvice
    val deviceName = state.deviceProfile?.let { "${it.manufacturer} ${it.model}".trim() }.orEmpty()
    Text("This phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    if (deviceName.isNotBlank()) {
        Text(deviceName, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when {
        state.scanningHardware -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Checking memory, processor, NPU, and GPU…")
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("onboarding-findings")) {
                advice.findings.forEach { FindingRow(it) }
            }
            HorizontalDivider()
            Recommendation(advice)
        }
    }

    state.importProgress?.let { progress ->
        val share = if (progress.totalBytes > 0) " · ${progress.bytesRead * 100 / progress.totalBytes}%" else ""
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("${progress.stage}$share", modifier = Modifier.testTag("onboarding-import-progress"))
        }
    }
    state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    val canRunLocally = advice?.canRunLocally ?: true
    if (advice?.canImportFiles ?: true) {
        val addLabel: @Composable () -> Unit = { Text(if (state.isImporting) "Adding…" else "Add a model file") }
        val addModifier = Modifier.fillMaxWidth().testTag("onboarding-add-file")
        if (canRunLocally) {
            Button(onClick = onAddModelFile, enabled = !state.isImporting, modifier = addModifier) { addLabel() }
        } else {
            OutlinedButton(onClick = onAddModelFile, enabled = !state.isImporting, modifier = addModifier) { addLabel() }
        }
        Text(
            "A GGUF (.gguf) or a LiteRT-LM package (.litertlm). Bram has no downloads of its own " +
                "yet: get the file in your browser, for example from Hugging Face, then add it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val serverButton: @Composable () -> Unit = { Text("Connect a server") }
    if (canRunLocally) {
        OutlinedButton(
            onClick = onConnectServer,
            enabled = !state.isImporting,
            modifier = Modifier.fillMaxWidth().testTag("onboarding-connect-server"),
        ) { serverButton() }
    } else {
        Button(
            onClick = onConnectServer,
            modifier = Modifier.fillMaxWidth().testTag("onboarding-connect-server"),
        ) { serverButton() }
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (advice.canRunLocally) "Start with" else "Best option",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(advice.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val examples = (advice.comfortable ?: advice.largest)?.examples.orEmpty()
        if (examples.isNotEmpty()) {
            Text(
                "For example ${examples.joinToString()}. Look for a file name ending in Q4_0.gguf.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        advice.reasons.forEach { reason ->
            Text("• $reason", style = MaterialTheme.typography.bodySmall)
        }
        if (advice.alternatives.isNotEmpty()) {
            Text("Also works", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            advice.alternatives.forEach { alternative ->
                Text("• $alternative", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
