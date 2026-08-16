package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.SkillImportOutcome
import io.github.kurue.bram.core.domain.SkillStore
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import org.json.JSONObject

/**
 * Lets the agent author a skill (or a new version of one) as a draft for the user to review. The
 * draft lands with no active version, so it is kept out of the system prompt until the user
 * activates it from Settings — an untrusted author never injects text into the prompt unreviewed.
 * Side-effecting on purpose, so the approval gate asks before it runs.
 */
class ProposeSkillTool(
    private val skillStore: SkillStore,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "propose_skill",
        description = "Author a new skill (or a new version of an existing one) as a draft for the " +
            "user to review and activate. A skill is a short procedure the agent follows whenever its " +
            "description matches the task. Drafts are NOT active until the user approves them, so " +
            "propose one whenever the user would benefit from a reusable procedure.",
        inputSchemaJson = """{"type":"object","properties":{"name":{"type":"string","description":"Skill name, 1-48 chars, no colons or line breaks"},"version":{"type":"string","description":"Semver, three numbers like 1.0.0"},"description":{"type":"string","description":"One line saying when to follow this skill, 1-500 chars"},"instructions":{"type":"string","description":"The steps to follow, written for the model to read"}},"required":["name","version","description","instructions"],"additionalProperties":false}""",
        readOnly = false,
        approvalScopeKeys = listOf(),
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return errorJson("bad_arguments", "Arguments were not valid JSON")
        val name = args.optString("name").trim()
        val version = args.optString("version").trim()
        val description = args.optString("description").trim()
        val instructions = args.optString("instructions").trim()
        if (name.isEmpty() || version.isEmpty() || description.isEmpty() || instructions.isEmpty()) {
            return errorJson("bad_arguments", "name, version, description, and instructions are all required")
        }
        val document = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("version: $version")
            appendLine("description: $description")
            appendLine("---")
            append(instructions)
        }
        return when (val outcome = skillStore.proposeDraft(document)) {
            is SkillImportOutcome.Imported -> {
                // What the draft supersedes, so the model knows what stays in effect: the active
                // version keeps driving the harness until the user activates the draft.
                val activeVersion = runCatching { skillStore.packages() }.getOrDefault(emptyList())
                    .firstOrNull { it.id == outcome.packageId }?.activeVersion
                val supersedeNote = if (activeVersion == null) {
                    "This is a new skill; nothing is active yet."
                } else {
                    "Version $activeVersion stays active until the user activates this draft."
                }
                JSONObject()
                    .put("packageId", outcome.packageId)
                    .put("version", outcome.version)
                    .put(
                        "note",
                        "Staged as a draft. It is NOT active yet; the user must activate it in " +
                            "Settings before it joins the system prompt. $supersedeNote To improve " +
                            "an existing skill, read it with read_skill first so the new version " +
                            "builds on what is active.",
                    )
                    .toString()
            }
            is SkillImportOutcome.Rejected -> errorJson("rejected", outcome.reason)
        }
    }

    private fun errorJson(code: String, message: String) = JSONObject()
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()
}
