package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.SkillStore
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * The read side of the skill loop.
 *
 * propose_skill alone was a one-way pipe: a model could stage a new version but never see what it
 * was improving, what version was active, or whether its draft had landed — so "improve the
 * weather skill" meant guessing. These two tools close the loop: list what exists, read what is
 * followed.
 *
 * Both are read-only and permission-free, so they take the AUTO fast path and cost no approval
 * interruptions. Drafts are listed by name and description only — an unreviewed skill's
 * instructions must not reach the model, or "never followed until activation" would stop being
 * true the moment a draft was proposed.
 */
class ListSkillsTool(
    private val skillStore: SkillStore,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "list_skills",
        description = "List installed skills: active ones (which the harness follows when their " +
            "description matches the task) and drafts awaiting the user's activation. Use before " +
            "improving a skill with propose_skill.",
        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        readOnly = true,
    )

    override suspend fun execute(argumentsJson: String): String {
        val packages = runCatching { skillStore.packages() }.getOrDefault(emptyList())
        val active = JSONArray()
        val drafts = JSONArray()
        for (pkg in packages) {
            val activeVersion = pkg.versions.firstOrNull { it.version == pkg.activeVersion }
            if (activeVersion != null) {
                active.put(
                    JSONObject()
                        .put("name", pkg.name)
                        .put("version", activeVersion.version)
                        .put("description", activeVersion.description),
                )
            }
            val draftVersion = pkg.draftVersion
            if (draftVersion != null) {
                pkg.versions.firstOrNull { it.version == draftVersion }?.let { draft ->
                    drafts.put(
                        JSONObject()
                            .put("name", pkg.name)
                            .put("version", draft.version)
                            .put("description", draft.description),
                    )
                }
            }
        }
        return JSONObject()
            .put(
                "note",
                "Active skills are followed when relevant. Drafts are staged by propose_skill and " +
                    "join the system prompt only after the user activates them.",
            )
            .put("active", active)
            .put("drafts", drafts)
            .toString()
    }
}

/** Reads one skill's active version: what to follow exactly, or what a new draft would supersede. */
class ReadSkillTool(
    private val skillStore: SkillStore,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "read_skill",
        description = "Read one active skill's version, description, and full instructions. Use to " +
            "follow the skill exactly, or as the starting point for improving it with " +
            "propose_skill at a higher version number. Drafted versions cannot be read until the " +
            "user activates them.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "name":{"type":"string","description":"Skill name, as list_skills reports it."}},
             "required":["name"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        val wanted = arguments.optString("name").trim()
        if (wanted.isEmpty()) return toolError("invalid_name", "A skill name is required")
        val packages = runCatching { skillStore.packages() }.getOrDefault(emptyList())
        val pkg = packages.firstOrNull { it.name.equals(wanted, ignoreCase = true) || it.id == wanted.lowercase() }
            ?: return toolError(
                "not_found",
                "No skill named \"$wanted\". Call list_skills to see what is installed.",
            )
        val activeVersion = pkg.versions.firstOrNull { it.version == pkg.activeVersion }
            ?: return toolError(
                "not_active",
                "\"${pkg.name}\" has a draft but no active version; the user must activate it in " +
                    "Skills before it can be read or followed.",
            )
        return JSONObject()
            .put("name", pkg.name)
            .put("version", activeVersion.version)
            .put("description", activeVersion.description)
            .put("instructions", activeVersion.instructions)
            .toString()
    }
}
