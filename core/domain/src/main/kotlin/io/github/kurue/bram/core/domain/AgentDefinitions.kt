package io.github.kurue.bram.core.domain

/**
 * A reusable agent: instructions and constraints layered on top of Bram's own identity, not a
 * duplicate model profile. The default Bram agent is what every chat and task already runs; a
 * definition narrows what a delegated child may do.
 *
 * First slice: definitions are built in, read-only, and delegated to one at a time. The fields a
 * future editor would expose are here from the start so persisted definitions can migrate additively.
 */
data class AgentDefinition(
    val id: String,
    val version: Int,
    val name: String,
    /** One line for the parent model: what this agent is for. Shown in the delegate tool. */
    val description: String,
    /** Layered after Bram's identity, exactly like profile instructions, never in place of it. */
    val instructions: String,
    /**
     * Which tools the child may be offered, by name. Empty means every read-only tool the parent
     * had; the read-only filter is applied regardless, so an allowlist can narrow but never arm.
     */
    val toolAllowlist: Set<String> = emptySet(),
    val maxOutputTokens: Int = 1_024,
    val maxToolTurns: Int = 4,
)

object AgentCatalog {
    /** What a run with no definition is: Bram itself, unbounded by an agent's constraints. */
    val DEFAULT = AgentDefinition(
        id = "bram",
        version = 1,
        name = "Bram",
        description = "Bram itself, with every tool the conversation allows.",
        instructions = "",
    )

    /**
     * The one delegated agent of the first slice: gathers and reads, changes nothing. Research,
     * review, and extraction are the shapes that only need a bounded answer back.
     */
    val RESEARCH = AgentDefinition(
        id = "research",
        version = 1,
        name = "Research",
        description = "Reads files, notes, memories, and the web to answer one question, changing nothing.",
        instructions = buildString {
            appendLine("You are running as Bram's research delegate for a single task.")
            appendLine("Answer the task you were given and nothing else: a bounded, factual answer,")
            appendLine("with sources named where you used them. You have read-only tools; do not")
            appendLine("claim to have changed, sent, or scheduled anything.")
        },
        toolAllowlist = setOf(
            "web_search", "web_fetch", "memory_search", "files_list", "file_read", "device_status",
            "scratch_note", "list_skills", "read_skill",
        ),
        maxOutputTokens = 1_024,
        maxToolTurns = 4,
    )

    fun all(): List<AgentDefinition> = listOf(DEFAULT, RESEARCH)

    fun find(id: String): AgentDefinition? = all().firstOrNull { it.id == id }
}
