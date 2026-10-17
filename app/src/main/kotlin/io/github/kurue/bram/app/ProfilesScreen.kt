package io.github.kurue.bram.app

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurue.bram.core.domain.ConfigDepth
import io.github.kurue.bram.core.domain.ConfigWorkload
import io.github.kurue.bram.core.domain.FlashAttentionMode
import io.github.kurue.bram.core.domain.HexFlags
import io.github.kurue.bram.core.domain.KvCacheType
import io.github.kurue.bram.core.domain.LoadMode
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.QuantCompatibility
import io.github.kurue.bram.core.domain.TuningDimension
import io.github.kurue.bram.core.domain.isHybridArchitecture
import io.github.kurue.bram.core.domain.isStreamableMoeArchitecture

/*
 * Profiles: a compact row per profile in the Models list, and a detail view that opens on tap.
 *
 * The old card put every action and setting on the card itself — full-width buttons, eight rows of
 * chips — so the list was a wall of controls. The row now carries what a person scans for (name,
 * backend and context, measured speed, whether it is loaded) and one action, load or unload. Every
 * other action and knob lives in the detail, split by job: Overview (what it is and whether it is
 * running), Performance (measuring it), and Settings (changing it).
 */

/** The measured prompt/decode speed of a profile's chosen backend, or null if never measured. */
private fun ModelProfile.measuredSpeed(): String? {
    val chosen = backendId.ifBlank { "" }
    val m = measurements.firstOrNull { it.backendId == chosen }
        ?: measurements.firstOrNull { it.isReference && chosen.isBlank() }
        ?: return null
    if (m.promptTokPerSec <= 0.0 || m.decodeTokPerSec <= 0.0) return null
    return "${m.promptTokPerSec.toInt()}/${m.decodeTokPerSec.toInt()} tok/s"
}

private fun profileSummary(profile: ModelProfile, model: LocalModelRecord, backend: RuntimeBackend): String =
    listOf(backend.label, formatTokens(profile.contextTokens), model.quantization, formatBytes(model.fileSizeBytes))
        .filter { it.isNotBlank() }
        .joinToString(" · ")

@Composable
private fun StatusChip(text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
internal fun ProfileRow(
    profile: ModelProfile,
    model: LocalModelRecord,
    backend: RuntimeBackend,
    loaded: Boolean,
    loading: Boolean,
    busy: Boolean,
    staleMeasurement: Boolean,
    onOpen: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    GlassSurface(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("profile-row"),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    profileSummary(profile, model, backend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        loading -> StatusChip("Loading")
                        loaded -> StatusChip("Loaded")
                    }
                    if (staleMeasurement) {
                        StatusChip("Re-measure", error = true)
                    } else if (profile.autoConfiguredAtEpochMillis == 0L) {
                        StatusChip("Not measured")
                    }
                    profile.measuredSpeed()?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val label = if (loaded) "Unload ${profile.name}" else "Load ${profile.name}"
            IconButton(
                onClick = if (loaded) onUnload else onLoad,
                enabled = !busy && !loading,
                modifier = Modifier.size(48.dp).semantics { contentDescription = label },
            ) {
                val tint = if (!busy && !loading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                if (loaded) StopIcon(tint, Modifier.size(22.dp)) else PlayIcon(tint, Modifier.size(22.dp))
            }
            ChevronRightIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(20.dp))
        }
    }
}

private enum class ProfileTab(val label: String) { OVERVIEW("Overview"), PERFORMANCE("Performance"), SETTINGS("Settings") }

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileDetail(
    profile: ModelProfile,
    model: LocalModelRecord,
    loaded: Boolean,
    loading: Boolean,
    busy: Boolean,
    tuning: Boolean,
    tuneStatus: String?,
    backend: RuntimeBackend,
    availableBackends: List<RuntimeBackend>,
    removesModelOnDelete: Boolean,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onUpdateProfile: (ModelProfile) -> Unit,
    onDeleteProfile: () -> Unit,
    onDuplicate: () -> Unit,
    onAutoConfigure: (ConfigWorkload, ConfigDepth) -> Unit,
    onBenchmark: () -> Unit,
    onSustainedBenchmark: () -> Unit,
    onCancelBenchmark: () -> Unit,
    benchmarking: Boolean,
    benchmarkStatus: String?,
    benchRuns: List<io.github.kurue.bram.core.domain.BenchRun>,
    currentFingerprint: String,
    onTuneBatch: () -> Unit,
    onTuneDimension: (TuningDimension) -> Unit,
    tuningDimension: TuningDimension?,
    staleMeasurement: Boolean,
    memoryBandwidth: io.github.kurue.bram.core.domain.MemoryBandwidth?,
    converting: Boolean,
    onConvertQuant: (String) -> Unit,
) {
    var tab by rememberSaveable(profile.id) { mutableStateOf(ProfileTab.OVERVIEW) }
    var workload by rememberSaveable(profile.id) { mutableStateOf(ConfigWorkload.CHAT) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable(profile.id) { mutableStateOf(false) }
    var draftName by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var editingPrompt by rememberSaveable(profile.id) { mutableStateOf(false) }
    var draftPrompt by rememberSaveable(profile.id) { mutableStateOf(profile.systemPrompt) }
    var confirmDelete by rememberSaveable(profile.id) { mutableStateOf(false) }
    // Settings the runtime reads at load time cannot change under a loaded model.
    val locked = loaded || loading || busy
    // Measuring reloads the model itself and restores what was loaded, so only work already in
    // flight blocks it.
    val measuring = loading || busy

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelHandle()
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Profiles") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    profileSummary(profile, model, backend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "More actions" }
                        .testTag("profile-more"),
                ) { MoreIcon(MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                    DropdownMenuItem(
                        text = { Text(if (profile.systemPrompt.isBlank()) "Add instructions" else "Instructions") },
                        onClick = { menuOpen = false; editingPrompt = true },
                    )
                    DropdownMenuItem(text = { Text("Duplicate") }, enabled = !busy, onClick = { menuOpen = false; onDuplicate() })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        enabled = !locked,
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }

        if (renaming) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Profile name") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    // An empty field keeps the old name rather than producing an unpickable row.
                    draftName.trim().takeIf(String::isNotEmpty)?.let { onUpdateProfile(profile.copy(name = it)) }
                    renaming = false
                }) { Text("Save") }
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
            }
        }
        if (editingPrompt) {
            OutlinedTextField(
                value = draftPrompt,
                onValueChange = { draftPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                label = { Text("Instructions for this profile") },
                supportingText = { Text("Added to Bram's own instructions, not replacing them.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onUpdateProfile(profile.copy(systemPrompt = draftPrompt.trim()))
                    editingPrompt = false
                }) { Text("Save") }
                TextButton(onClick = { draftPrompt = profile.systemPrompt; editingPrompt = false }) { Text("Cancel") }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ProfileTab.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, ProfileTab.entries.size),
                    modifier = Modifier.testTag("profile-tab-${entry.name.lowercase()}"),
                    icon = {},
                ) { Text(entry.label, maxLines = 1) }
            }
        }

        when (tab) {
            ProfileTab.OVERVIEW -> {
                GlassSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FactRow("Status", if (loading) "Loading" else if (loaded) "Loaded" else "Not loaded")
                        FactRow("Backend", backend.label)
                        FactRow("Context", formatTokens(profile.contextTokens))
                        FactRow("Measured", profile.measuredSpeed() ?: "Not yet")
                        FactRow("File", "${model.quantization} · ${formatBytes(model.fileSizeBytes)}")
                    }
                }
                if (staleMeasurement) {
                    Note("Measured on a different device or build. Re-measure under Performance.", error = true)
                }
                // Import no longer tunes on its own, so a fresh profile offers it here: optional,
                // a few minutes, and cancellable.
                if (profile.autoConfiguredAtEpochMillis == 0L && !staleMeasurement) {
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Not measured on this phone yet", fontWeight = FontWeight.SemiBold)
                            Note(
                                "Auto-configure tries each backend and setting, keeps the fastest one that " +
                                    "agrees with the CPU, and can be cancelled. It takes a few minutes.",
                            )
                            OutlinedButton(
                                onClick = { onAutoConfigure(ConfigWorkload.CHAT, ConfigDepth.QUICK) },
                                enabled = !measuring,
                                modifier = Modifier.testTag("offer-auto-configure"),
                            ) { Text("Auto-configure · about 2 min") }
                        }
                    }
                }
                if (isHybridArchitecture(model.architecture)) {
                    Note("This architecture re-reads the whole conversation each turn, so long chats get slower.")
                }
                if (!model.hasChatTemplate) {
                    Note("This file has no chat template, so Bram uses a built-in one that cannot offer tools.")
                }
                Button(
                    onClick = if (loaded) onUnload else onLoad,
                    enabled = !busy && !loading,
                    modifier = Modifier.fillMaxWidth().testTag(if (loaded) "unload-profile" else "load-profile"),
                ) { Text(if (loading) "Loading…" else if (loaded) "Unload" else "Load") }
            }

            ProfileTab.PERFORMANCE -> {
                Note(
                    "Auto-configure measures backends and settings against the CPU within a time " +
                        "budget, most important first, and keeps a change only when it is clearly faster.",
                )
                SectionLabel("Tune for")
                ChipRow {
                    ConfigWorkload.entries.forEach { entry ->
                        FilterChip(
                            selected = workload == entry,
                            onClick = { workload = entry },
                            label = { Text(entry.label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAutoConfigure(workload, ConfigDepth.QUICK) },
                        enabled = !measuring,
                        modifier = Modifier.weight(1f).testTag("configure-quick"),
                    ) { Text("Quick · 2 min") }
                    OutlinedButton(
                        onClick = { onAutoConfigure(workload, ConfigDepth.THOROUGH) },
                        enabled = !measuring,
                        modifier = Modifier.weight(1f).testTag("configure-thorough"),
                    ) { Text("Thorough · 10 min") }
                }
                if (benchmarking) {
                    // Cancel sits beside the live step: a sustained run is five minutes long, and the
                    // status is what says how far along it is.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            benchmarkStatus ?: "Benchmarking…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onCancelBenchmark, modifier = Modifier.testTag("cancel-benchmark")) {
                            Text("Cancel")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onBenchmark,
                            enabled = !measuring,
                            modifier = Modifier.weight(1f).testTag("benchmark-profile"),
                        ) { Text("Benchmark") }
                        OutlinedButton(
                            onClick = onSustainedBenchmark,
                            enabled = !measuring,
                            modifier = Modifier.weight(1f).testTag("benchmark-sustained"),
                        ) { Text("Sustained 5 min") }
                    }
                }
                ProfileCeilingPill(profile, model, memoryBandwidth)
                BenchRunSummary(benchRuns, currentFingerprint, model, memoryBandwidth)
                if (model.tensorTypeCounts.isNotEmpty()) {
                    val report = QuantCompatibility.report(model.tensorTypeCounts)
                    if (report.npu.sharePercent < 100) {
                        SectionLabel("NPU compatibility")
                        Note(
                            "The NPU can run ${report.npu.sharePercent}% of this file's weight tensors. " +
                                "A converted copy can run fully on it.",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onConvertQuant("q4_0") }, enabled = !busy && !converting) {
                                Text(if (converting) "Converting…" else "Convert to Q4_0")
                            }
                            TextButton(onClick = { onConvertQuant("q8_0") }, enabled = !busy && !converting) {
                                Text("Convert to Q8_0")
                            }
                        }
                    }
                }
            }

            ProfileTab.SETTINGS -> ProfileSettings(
                profile = profile,
                model = model,
                backend = backend,
                availableBackends = availableBackends,
                loaded = loaded,
                loading = loading,
                locked = locked,
                busy = busy,
                measuring = measuring,
                tuning = tuning,
                tuneStatus = tuneStatus,
                tuningDimension = tuningDimension,
                onUnload = onUnload,
                onUpdateProfile = onUpdateProfile,
                onTuneBatch = onTuneBatch,
                onTuneDimension = onTuneDimension,
            )
        }

        Text(
            "${model.layerCount.takeIf { it > 0 } ?: "?"} layers · trained context " +
                "${formatTokens(model.trainedContextTokens)} · SHA ${model.sha256.take(12)}…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${profile.name}?") },
            text = {
                Text(
                    if (removesModelOnDelete) {
                        "This is the model's only profile, so its imported copy is removed too."
                    } else {
                        "This removes the profile and its settings. The imported model stays on this device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteProfile() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Every knob the old card held, unchanged in behavior, grouped under one tab. */
@Composable
private fun ProfileSettings(
    profile: ModelProfile,
    model: LocalModelRecord,
    backend: RuntimeBackend,
    availableBackends: List<RuntimeBackend>,
    loaded: Boolean,
    loading: Boolean,
    locked: Boolean,
    busy: Boolean,
    measuring: Boolean,
    tuning: Boolean,
    tuneStatus: String?,
    tuningDimension: TuningDimension?,
    onUnload: () -> Unit,
    onUpdateProfile: (ModelProfile) -> Unit,
    onTuneBatch: () -> Unit,
    onTuneDimension: (TuningDimension) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loaded && !loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note("Unload to change backend, context, threads, or batch.")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onUnload, enabled = !busy) { Text("Unload") }
            }
        }
        ProfileSettingPills(profile, backend)

        SectionLabel("Backend")
        ChipRow {
            availableBackends.forEach { candidate ->
                FilterChip(
                    selected = candidate == backend,
                    onClick = {
                        onUpdateProfile(profile.copy(backendId = if (candidate == RuntimeBackend.CPU) "" else candidate.name))
                    },
                    enabled = !locked,
                    label = { Text(candidate.label) },
                )
            }
        }

        SectionLabel("Context")
        ChipRow {
            contextOptions(model).forEach { tokens ->
                FilterChip(
                    selected = tokens == profile.contextTokens,
                    onClick = { onUpdateProfile(profile.copy(contextTokens = tokens)) },
                    enabled = !locked,
                    label = { Text(formatTokens(tokens)) },
                )
            }
        }

        SectionLabel("Sampling")
        SamplerControls(profile = profile, enabled = !busy, onUpdateProfile = onUpdateProfile)

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Reasoning", Modifier.weight(1f))
            Switch(
                checked = profile.thinkingEnabled,
                onCheckedChange = { onUpdateProfile(profile.copy(thinkingEnabled = it)) },
                enabled = !busy,
            )
        }

        // Context parameters rather than sampling ones: they change how the load builds the
        // attention cache, so measurements take them together with the backend.
        SectionLabel("Flash attention")
        ChipRow {
            FlashAttentionMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == profile.flashAttention,
                    onClick = {
                        // A quantized V cache needs flash attention, so turning it off returns the
                        // cache to F16 rather than saving an unusable combination.
                        val kv = if (mode == FlashAttentionMode.OFF) KvCacheType.F16 else profile.kvCacheType
                        onUpdateProfile(profile.copy(flashAttention = mode, kvCacheType = kv))
                    },
                    enabled = !busy,
                    label = { Text(mode.label) },
                )
            }
        }

        SectionLabel("Attention memory")
        ChipRow {
            KvCacheType.entries.forEach { type ->
                val allowed = type == KvCacheType.F16 || profile.flashAttention != FlashAttentionMode.OFF
                FilterChip(
                    selected = type == profile.kvCacheType,
                    onClick = { onUpdateProfile(profile.copy(kvCacheType = type)) },
                    enabled = !busy && allowed,
                    label = { Text(type.label) },
                )
            }
        }
        Note(profile.kvCacheType.summary)

        // Only for MoE architectures whose routed experts can be read from flash.
        if (isStreamableMoeArchitecture(model.architecture)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Stream experts", Modifier.weight(1f))
                Switch(
                    checked = profile.streamExperts,
                    onCheckedChange = { onUpdateProfile(profile.copy(streamExperts = it)) },
                    enabled = !busy,
                )
            }
            Note("Reads each token's experts from flash, so a mixture-of-experts model larger than RAM runs, more slowly.")
            if (profile.streamExperts) {
                SectionLabel("Expert cache")
                ChipRow {
                    listOf(0 to "Auto", 1024 to "1 GiB", 2048 to "2 GiB", 4096 to "4 GiB").forEach { (mb, label) ->
                        FilterChip(
                            selected = profile.streamCacheMb == mb,
                            onClick = { onUpdateProfile(profile.copy(streamCacheMb = mb)) },
                            enabled = !busy,
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        SectionLabel("Batch")
        ChipRow {
            batchPresets.forEach { preset ->
                FilterChip(
                    selected = preset.matches(profile),
                    onClick = { onUpdateProfile(profile.copy(batchTokens = preset.batch, ubatchTokens = preset.ubatch)) },
                    enabled = !locked,
                    label = { Text(preset.label) },
                )
            }
            TextButton(onClick = onTuneBatch, enabled = !measuring) { Text(if (tuning) "Tuning…" else "Tune") }
        }
        tuneStatus?.let { Note(it) }

        SectionLabel("Decode tuning")
        TuningDimensionRow(
            label = "Threads",
            isDefault = profile.threads == 0,
            chosen = profile.threads.takeIf { it > 0 }?.let { "$it threads" },
            measuring = tuningDimension == TuningDimension.THREADS,
            enabled = !busy,
            onDefault = { onUpdateProfile(profile.copy(threads = 0)) },
            onTune = { onTuneDimension(TuningDimension.THREADS) },
        )
        SectionLabel("Decode threads")
        ChipRow {
            val cores = Runtime.getRuntime().availableProcessors()
            (listOf(0) + listOf(2, 3, 4, 5, 6, 8).filter { it <= cores }).forEach { count ->
                FilterChip(
                    selected = profile.decodeThreads == count,
                    onClick = { onUpdateProfile(profile.copy(decodeThreads = count)) },
                    enabled = !locked,
                    label = { Text(if (count == 0) "Same" else "$count") },
                )
            }
        }
        TuningDimensionRow(
            label = "CPU mask",
            isDefault = profile.cpuMask.isEmpty(),
            chosen = profile.cpuMask.takeIf(String::isNotEmpty)?.let { "0x$it" },
            measuring = tuningDimension == TuningDimension.CPU_MASK,
            enabled = !busy,
            onDefault = { onUpdateProfile(profile.copy(cpuMask = "", cpuStrict = false)) },
            onTune = { onTuneDimension(TuningDimension.CPU_MASK) },
        )
        TuningDimensionRow(
            label = "Poll",
            isDefault = profile.poll < 0,
            chosen = profile.poll.takeIf { it >= 0 }?.let { "$it" },
            measuring = tuningDimension == TuningDimension.POLL,
            enabled = !busy,
            onDefault = { onUpdateProfile(profile.copy(poll = -1)) },
            onTune = { onTuneDimension(TuningDimension.POLL) },
        )
        SectionLabel("Load mode")
        ChipRow {
            LoadMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == profile.loadMode,
                    onClick = { onUpdateProfile(profile.copy(loadMode = mode)) },
                    enabled = !locked,
                    label = { Text(mode.label) },
                )
            }
            TextButton(onClick = { onTuneDimension(TuningDimension.LOAD_MODE) }, enabled = !measuring) {
                Text(if (tuningDimension == TuningDimension.LOAD_MODE) "Tuning…" else "Tune")
            }
        }
        if (backend == RuntimeBackend.HEXAGON) {
            TuningDimensionRow(
                label = "Hexagon",
                isDefault = profile.hexFlags.isDefault,
                chosen = if (profile.hexFlags.isDefault) null else "HMX + host buffers",
                measuring = tuningDimension == TuningDimension.HEX_FLAGS,
                enabled = !busy,
                onDefault = { onUpdateProfile(profile.copy(hexFlags = HexFlags())) },
                onTune = { onTuneDimension(TuningDimension.HEX_FLAGS) },
            )
        }
    }
}
