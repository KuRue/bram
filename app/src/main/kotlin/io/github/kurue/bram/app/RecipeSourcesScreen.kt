package io.github.kurue.bram.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RecipeSourcesScreen(
    state: RecipeSourcesUi,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
    onAccept: () -> Unit,
    onDismissReview: () -> Unit,
    onRollback: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelHandle()
        DestinationHeader("Recipe sources", "Settings", onBack)
        Text("Snapshots suggest measured settings. Local validation is required before settings can be applied.")
        Text("Applying recipes is not available in this build.", style = MaterialTheme.typography.bodySmall)
        state.error?.let { ErrorCard(it) }
        state.message?.let { Text(it) }
        state.bundled?.let { snapshot ->
            SourceSnapshotCard(snapshot, "Bundled with Bram · available offline", inspectionTag = "recipe-inspect-bundled")
        }
        state.sources.forEach { source ->
            SourceSnapshotCard(source.current, "External · available offline", source.url)
            TextButton(
                onClick = { onReview(source.url) }, enabled = !state.busy,
                modifier = Modifier.testTag("recipe-refresh"),
            ) { Text("Check for update") }
            source.previous?.let { previous ->
                TextButton(onClick = { onRollback(source.url) }, enabled = !state.busy) {
                    Text("Restore ${previous.index.revision}")
                }
            }
            TextButton(onClick = { removing = source.url }, enabled = !state.busy) { Text("Remove source") }
        }
        OutlinedTextField(
            value = url, onValueChange = { url = it.take(2048) },
            label = { Text("HTTPS index or GitHub repository@commit") },
            placeholder = { Text("https://example.org/recipes/index.json") },
            modifier = Modifier.fillMaxWidth().testTag("recipe-source-url"),
            enabled = !state.busy, singleLine = false,
        )
        Text("For GitHub use owner/repo@ followed by a full 40-character commit. The index must be at the repository root.", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { onReview(url) }, enabled = !state.busy && url.isNotBlank(),
            modifier = Modifier.testTag("recipe-source-review"),
        ) { Text("Download for review") }
        if (state.busy) {
            CircularProgressIndicator()
            Text("Reading source…")
        }
        state.review?.let { review ->
            SourceSnapshotCard(review.snapshot, "Review · not active", review.url)
            Text(
                if (review.snapshot.pin == review.expectedPin) "This is the pinned snapshot; no update is needed."
                else "Trust this snapshot only if you trust its publisher. SHA-256 checks the bytes; it does not verify the author. Every update needs review.",
            )
            review.expectedPin?.let { Text("Replaces pin: $it", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onAccept, enabled = !state.busy, modifier = Modifier.testTag("recipe-source-accept")) {
                Text(if (review.expectedPin == null) "Trust and pin snapshot" else "Pin reviewed snapshot")
            }
            TextButton(onClick = onDismissReview, enabled = !state.busy) { Text("Discard review") }
        }
    }
    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove source?") },
            text = { Text("Remove its cached suggestions and snapshots. Existing profile settings are kept.") },
            confirmButton = {
                TextButton(onClick = { onRemove(target); removing = null }, enabled = !state.busy) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceSnapshotCard(snapshot: RecipeSnapshot, status: String, url: String? = null, inspectionTag: String = "recipe-inspect") {
    var expanded by rememberSaveable(snapshot.pin) { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(snapshot.index.source.name, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.labelMedium)
            Text("Revision ${snapshot.index.revision} · ${snapshot.recipes.size} recipes")
            url?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            SelectionContainer { Text("SHA-256: ${snapshot.pin}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag(inspectionTag)) {
                Text(if (expanded) "Hide recipes" else "Inspect recipes")
            }
            if (expanded) snapshot.recipes.forEach { recipe ->
                Text(recipe.id, style = MaterialTheme.typography.titleSmall)
                Text("${recipe.maintainer} · ${recipe.evidenceDate}", style = MaterialTheme.typography.bodySmall)
                Text("Devices: ${recipe.match.models.joinToString()} · ${recipe.match.socs.joinToString()}")
                Text("Models: ${recipe.model.architectures.joinToString()} · ${recipe.model.quantizations.joinToString()}")
                val proposal = recipe.proposal
                val settings = listOfNotNull(
                    proposal.backend?.let { "Backend: ${it.wire}" },
                    proposal.contextTokens?.let { "Context: $it tokens" },
                    proposal.threads?.let { "Threads: $it" },
                    proposal.decodeThreads?.let { "Decode threads: $it" },
                    proposal.cpuMask?.let { "CPU mask: $it" },
                    proposal.poll?.let { "Polling: $it" },
                    proposal.flashAttention?.let { "Attention: $it" },
                    proposal.kvCache?.let { "KV cache: $it" },
                    proposal.batchTokens?.let { "Batch: $it tokens" },
                    proposal.ubatchTokens?.let { "Micro-batch: $it tokens" },
                    proposal.loadMode?.let { "Loading: $it" },
                    proposal.threadPriority?.let { "Thread priority: $it" },
                )
                Text(settings.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                Text("Evidence: ${recipe.evidence.promptTokPerSec ?: "unknown"} prompt / ${recipe.evidence.decodeTokPerSec ?: "unknown"} decode tok/s")
            }
        }
    }
}
