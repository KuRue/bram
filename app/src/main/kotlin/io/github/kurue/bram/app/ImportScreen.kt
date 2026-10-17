package io.github.kurue.bram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.kurue.bram.core.domain.MemoryFit

/**
 * Confirms a picked model file before Bram copies it: name, size, and whether it fits this phone,
 * with one Import action. Both the setup guide and Models › Add open the same picker, so both land
 * here. Import no longer tunes anything; the new profile offers that once it exists.
 */
@Composable
internal fun ImportConfirmOverlay(
    state: AppUiState,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    val staged = state.stagedImport ?: return
    // A Dialog, not an in-tree overlay: Models is a bottom sheet, which is its own window, and an
    // overlay drawn in the activity's window sat behind the sheet's scrim.
    Dialog(
        onDismissRequest = { if (!state.isImporting) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding()
            .padding(16.dp)
            .testTag("import-confirm"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add this model?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    staged.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        if (staged.isLiteRt) "LiteRT-LM package" else "GGUF",
                        staged.parts.takeIf { it > 1 }?.let { "$it parts" },
                        staged.totalBytes.takeIf { it > 0 }?.let(::formatBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            staged.fit?.let { fit ->
                val (marker, color) = when {
                    !fit.storageOk || fit.memory == MemoryFit.TOO_LARGE -> "×" to MaterialTheme.colorScheme.error
                    fit.memory == MemoryFit.LARGE -> "!" to MaterialTheme.colorScheme.tertiary
                    fit.memory == MemoryFit.COMFORTABLE -> "✓" to MaterialTheme.colorScheme.primary
                    else -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(marker, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    Text(fit.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("import-fit"))
                }
            }
            if (!staged.isLiteRt) {
                Text(
                    "Bram copies the file into its own storage and creates a profile that loads with safe " +
                        "settings. Measuring this phone's best settings is optional, from the profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.importProgress?.let { progress ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (progress.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(progress.stage, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onImport,
                    enabled = !state.isImporting && staged.fit?.storageOk != false,
                    modifier = Modifier.weight(1f).testTag("import-confirm-button"),
                ) { Text(if (state.isImporting) "Importing…" else "Import") }
                TextButton(onClick = onCancel, enabled = !state.isImporting) { Text("Cancel") }
            }
        }
    }
}
}
