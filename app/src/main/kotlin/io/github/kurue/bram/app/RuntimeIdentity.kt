package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.LocalModelRecord

/** One line of the runtime identity: what the value is, and the value itself. */
data class RuntimeIdentityLine(val label: String, val value: String)

/**
 * What a recipe has to name about this build, in the words a contributor copies from the screen.
 *
 * A recipe records the engine commit it was measured on and the exact SHA-256 of the model file,
 * alongside the device and OS identity. Bram knows all of them; this is the list that puts them
 * somewhere copyable, so writing a recipe does not start with a terminal.
 */
internal fun runtimeIdentityLines(
    engineBuild: String,
    deviceIdentity: String,
    model: LocalModelRecord?,
): List<RuntimeIdentityLine> = listOf(
    RuntimeIdentityLine("Engine", engineBuild.ifBlank { "unknown — the native runtime did not report a commit" }),
    RuntimeIdentityLine("Device", deviceIdentity.ifBlank { "unknown" }),
    RuntimeIdentityLine(
        "Model file",
        when {
            model == null -> "no local model"
            model.parts.isNotEmpty() -> "${model.fileName} (split into parts; a recipe names a single file's hash)"
            else -> model.fileName
        },
    ),
    RuntimeIdentityLine(
        "Model SHA-256",
        when {
            model == null -> "unavailable until a local model is imported"
            model.sha256.isBlank() -> "unavailable for this copy"
            else -> model.sha256
        },
    ),
)
