package io.github.kurue.bram.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import android.content.ClipboardManager
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.RecipeDraft
import io.github.kurue.bram.core.domain.RecipeExport
import io.github.kurue.bram.core.domain.RecipeFidelity
import io.github.kurue.bram.core.domain.RecipeVerdict

@Composable
internal fun RecipeSourcesScreen(
    state: RecipeSourcesUi,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
    onAccept: (String?) -> Unit,
    onDismissReview: () -> Unit,
    onRollback: (String) -> Unit,
    onRemove: (String) -> Unit,
    applicationState: AppUiState? = null,
    onValidate: (String, String) -> Unit = { _, _ -> },
    onSaveRecipe: (RecipeExport) -> Unit = {},
) {
    var url by rememberSaveable { mutableStateOf("") }
    var chosenProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelHandle()
        DestinationHeader("Recipe sources", "Settings", onBack)
        Text("Snapshots suggest measured settings. Local validation is required before settings can be applied.")
        applicationState?.let { app ->
            // One profile choice serves both sections: matches to validate, and the measurement to
            // share. They describe the same profile, so they cannot disagree about which one.
            val chosen = exportableProfileId(app, chosenProfileId)
            RecipeMatchesSection(app, chosen, { chosenProfileId = it }, onValidate)
            RecipeExportSection(app, app.profiles.firstOrNull { it.id == chosen }, onSaveRecipe)
        }
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
                TextButton(onClick = { onRollback(source.url) }, enabled = !state.busy && source.canRollback) {
                    Text("Restore ${previous.index.revision}")
                }
                if (!source.canRollback) Text("Previous snapshot uses another or unsigned key. A new key review is required to restore it.", style = MaterialTheme.typography.bodySmall)
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
            var keyApproved by rememberSaveable(review.url, review.expectedState, review.snapshot.pin, review.snapshot.signature?.publicKey) { mutableStateOf(false) }
            SourceSnapshotCard(review.snapshot, "Review · not active", review.url)
            if (review.needsKeyApproval) {
                Text(if (review.changesKey) "Signing key changed" else "Trust a signing key", modifier = Modifier.testTag("recipe-key-review"))
                review.expectedKey?.let { Text("Pinned key SHA-256: ${recipeSha256(java.util.Base64.getDecoder().decode(it))}", style = MaterialTheme.typography.bodySmall) }
                Text("Compare the new key fingerprint with the publisher through a separate trusted channel. A valid signature proves control of this key; it does not independently identify its owner.", style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(checked = keyApproved, onCheckedChange = { keyApproved = it }, enabled = !state.busy,
                        modifier = Modifier.testTag("recipe-key-approve"))
                    Text(if (review.changesKey) "I verified and approve replacing the pinned signing key" else "I verified and trust this signing key")
                }
            } else Text("Signature verified with the pinned signing key. Review the snapshot changes before accepting.")
            review.expectedPin?.let { Text("Replaces pin: $it", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { onAccept(if (review.needsKeyApproval) review.snapshot.signature?.fingerprint else null) },
                enabled = !state.busy && (!review.needsKeyApproval || keyApproved), modifier = Modifier.testTag("recipe-source-accept")) {
                Text(if (review.changesKey) "Replace key and pin snapshot" else if (review.needsKeyApproval) "Trust key and pin snapshot" else "Pin reviewed snapshot")
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
internal fun RecipeMatchesSection(
    state: AppUiState,
    chosenProfileId: String?,
    onChooseProfile: (String) -> Unit,
    onValidate: (String, String) -> Unit,
) {
    val profile = state.profiles.firstOrNull { it.id == chosenProfileId }
        ?: state.profiles.firstOrNull { it.id == state.activeProfileId } ?: state.profiles.firstOrNull()
    Text("Local configuration", style = MaterialTheme.typography.titleMedium)
    if (profile == null) {
        Text("Add a local GGUF model to find recipes for its profile.", style = MaterialTheme.typography.bodySmall)
        return
    }
    state.profiles.forEach { candidate ->
        TextButton(onClick = { onChooseProfile(candidate.id) }, modifier = Modifier.testTag("recipe-profile-${candidate.id}")) {
            Text(if (candidate.id == profile.id) "✓ ${candidate.name}" else candidate.name)
        }
    }
    if (profile.recipeProvenance.isNotBlank()) {
        Text("Last validated recipe: ${profile.recipeProvenance}", style = MaterialTheme.typography.bodySmall)
    }
    val previews = recipePreviews(state, profile.id)
    if (previews.isEmpty()) Text("No accepted recipe matches this phone and model.", modifier = Modifier.testTag("recipe-no-matches"))
    previews.forEach { preview ->
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preview.candidate.recipe.id, style = MaterialTheme.typography.titleSmall)
                Text("${preview.sourceName} · ${preview.revision} · ${if (preview.official) "Bundled" else "External"}")
                Text(if (preview.candidate.fidelity == RecipeFidelity.EXACT) "Exact match · local validation still required" else "Compatible seed · local validation required")
                preview.candidate.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Evidence: ${preview.candidate.recipe.evidenceDate}", style = MaterialTheme.typography.bodySmall)
                preview.candidate.changes.forEach { change -> Text("${change.label}: ${change.from} → ${change.to}") }
                preview.candidate.recipe.limits.incompatible.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                preview.record?.let { record ->
                    val label = when (record.verdict) {
                        RecipeVerdict.REJECTED -> "Excluded for this device, build, model and settings"
                        RecipeVerdict.KEPT -> "Current settings kept"
                        RecipeVerdict.INTERRUPTED -> "Interrupted"
                        RecipeVerdict.CHECKING -> "Checking"
                        RecipeVerdict.APPLIED -> "Applied after local validation"
                    }
                    Text("$label: ${record.detail}", modifier = Modifier.testTag("recipe-validation-record"))
                    if (record.incumbentMillis > 0 && record.proposedMillis > 0) {
                        Text("Paired replay time: ${record.incumbentMillis} → ${record.proposedMillis} ms in total", style = MaterialTheme.typography.bodySmall)
                    }
                }
                preview.blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Requires 90% agreement across every CPU reference token (up to 24), over 5% improvement in both timing pairs, and a context check. Up to 10 minutes; Cancel keeps the current profile.", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { onValidate(profile.id, preview.key) },
                    enabled = preview.blocked == null && !state.recipeSources.busy &&
                        !state.isGenerating && !state.isLoadingModel && !state.isLoadingLiteRt &&
                        !state.isValidatingAccelerator && !state.isImporting && !state.measuringBandwidth &&
                        state.convertingProfileId == null && state.benchmarkingProfileId == null &&
                        state.autoConfigure?.finished != false,
                    modifier = Modifier.testTag("recipe-validate"),
                ) { Text(if (preview.record?.verdict == RecipeVerdict.REJECTED) "Retry validation and apply if faster" else "Validate and apply if faster") }
            }
        }
    }
}

/**
 * The other direction from a source: this phone measured something, and the person may want to
 * publish it. Nothing is exported until they ask, and the document is shown before it is saved —
 * including the sentence that says what is deliberately not in it.
 */
@Composable
internal fun RecipeExportSection(state: AppUiState, profile: ModelProfile?, onSave: (RecipeExport) -> Unit) {
    Text("Share this measurement", style = MaterialTheme.typography.titleMedium)
    if (profile == null) {
        Text("Add a local GGUF model first: a recipe describes one profile's measurement.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var maintainer by rememberSaveable { mutableStateOf("") }
    var showDocument by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val draft = remember(state, profile, maintainer) { recipeExportFor(state, profile.id, maintainer.trim()) }
    OutlinedTextField(
        value = maintainer,
        onValueChange = { maintainer = it.take(120) },
        label = { Text("Maintainer (your name or handle, as it should appear)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("recipe-maintainer"),
    )
    when (draft) {
        is RecipeDraft.NotReady -> Text(draft.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("recipe-export-not-ready"))
        is RecipeDraft.Ready -> {
            val export = draft.export
            Text(export.provenance, style = MaterialTheme.typography.bodySmall)
            Text(
                "This document names the device, the engine build, the model's content hash, the " +
                    "settings and the measurements. It cannot carry a prompt, a conversation, a file " +
                    "name, a note, or anything you typed: the export has no field for them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showDocument = !showDocument }, modifier = Modifier.testTag("recipe-export-preview")) {
                Text(if (showDocument) "Hide the document" else "Preview the document")
            }
            if (showDocument) {
                GlassSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectionContainer { Text(export.document, style = MaterialTheme.typography.bodySmall) }
                        Text("Index entry to paste beside it:", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(export.indexEntry, style = MaterialTheme.typography.bodySmall) }
                        SelectionContainer { Text("SHA-256 ${export.sha256}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Button(onClick = { onSave(export) }, modifier = Modifier.testTag("recipe-export-save")) {
                Text("Save recipe file")
            }
            TextButton(
                onClick = { clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Bram recipe", export.document)) },
                modifier = Modifier.testTag("recipe-export-copy"),
            ) { Text("Copy JSON") }
            Text(
                "Add it to a recipe repository, or to your own source, with the index entry and hash above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            if (url != null) {
                Text(snapshot.signature?.let { "Verified signature · key SHA-256: ${it.fingerprint}" }
                    ?: "Unsigned cached snapshot · check for update to review a signing key", style = MaterialTheme.typography.bodySmall)
            }
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
