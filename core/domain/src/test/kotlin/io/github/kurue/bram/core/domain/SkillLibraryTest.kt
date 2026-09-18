package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillLibraryTest {

    private fun document(
        name: String = "Weather Scout",
        version: String = "1.0.0",
        description: String = "Check conditions before planning outdoors",
        instructions: String = "Before any outdoor plan, report the current weather and note rain risks.",
        author: String? = null,
        tools: String? = null,
        permissions: String? = null,
    ) = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("version: $version")
        appendLine("description: $description")
        author?.let { appendLine("author: $it") }
        tools?.let { appendLine("tools: $it") }
        permissions?.let { appendLine("permissions: $it") }
        appendLine("---")
        append(instructions)
    }.trimEnd()

    @Test
    fun `a new skill imports as active`() {
        val library = SkillLibrary()
        val outcome = library.importDocument(document(), nowMillis = 1000L)

        assertEquals(SkillImportOutcome.Imported("weather-scout", "1.0.0", stagedAsDraft = false), outcome)
        val pkg = library.packages().single()
        assertEquals("1.0.0", pkg.activeVersion)
        assertNull(pkg.draftVersion)
        assertEquals("1.0.0", library.activeSkills().single().version)
    }

    @Test
    fun `a new version of a known skill lands as a draft`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)

        val outcome = library.importDocument(
            document(version = "1.1.0", description = "Also check the forecast"),
            nowMillis = 2000L,
        )

        assertEquals(SkillImportOutcome.Imported("weather-scout", "1.1.0", stagedAsDraft = true), outcome)
        val pkg = library.packages().single()
        assertEquals("1.0.0", pkg.activeVersion)
        assertEquals("1.1.0", pkg.draftVersion)
        // The active version still resolves, and the draft does not leak into the prompt.
        assertEquals("1.0.0", library.activeSkills().single().version)
    }

    @Test
    fun `activating a draft swaps it to active and keeps the previous version for rollback`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)
        library.importDocument(document(version = "1.1.0"), nowMillis = 2000L)

        assertEquals(SkillActionOutcome.Ok, library.activateDraft("weather-scout", nowMillis = 3000L))

        val pkg = library.packages().single()
        assertEquals("1.1.0", pkg.activeVersion)
        assertNull(pkg.draftVersion)
        assertEquals("1.1.0", library.activeSkills().single().version)
    }

    @Test
    fun `rollback restores the version that was active before`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)
        library.importDocument(document(version = "1.1.0"), nowMillis = 2000L)
        library.activateDraft("weather-scout", nowMillis = 3000L)

        assertEquals(SkillActionOutcome.Ok, library.rollback("weather-scout", nowMillis = 4000L))

        assertEquals("1.0.0", library.packages().single().activeVersion)
        assertEquals("1.0.0", library.activeSkills().single().version)
    }

    @Test
    fun `rollback with a single version fails`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)

        val outcome = library.rollback("weather-scout", nowMillis = 2000L)

        assertTrue(outcome is SkillActionOutcome.Failed)
        assertEquals("1.0.0", library.packages().single().activeVersion)
    }

    @Test
    fun `rollback never promotes an unapproved draft`() {
        // The recorded bug shape: import 1.0.0, draft 1.1.0, activate it, draft 1.2.0. A rollback
        // used to take the newest non-active version — the unreviewed 1.2.0 — and make it active
        // while it was still listed as the draft.
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)
        library.proposeDraft(document(version = "1.1.0"), nowMillis = 2000L)
        library.activateDraft("weather-scout", nowMillis = 3000L)
        library.proposeDraft(document(version = "1.2.0"), nowMillis = 4000L)

        assertEquals(SkillActionOutcome.Ok, library.rollback("weather-scout", nowMillis = 5000L))

        val pkg = library.packages().single()
        assertEquals("the last approved version is 1.0.0", "1.0.0", pkg.activeVersion)
        assertEquals("the unreviewed draft is still just a draft", "1.2.0", pkg.draftVersion)
        assertEquals("1.0.0", library.activeSkills().single().version)
    }

    @Test
    fun `rollback with only an unapproved draft staged refuses`() {
        // Import 1.0.0 (approved) then stage 1.1.0 from the agent: there is nothing approved to go
        // back to, so the draft must not be activated by a rollback.
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)
        library.proposeDraft(document(version = "1.1.0"), nowMillis = 2000L)

        val outcome = library.rollback("weather-scout", nowMillis = 3000L)

        assertTrue(outcome is SkillActionOutcome.Failed)
        assertEquals("1.0.0", library.packages().single().activeVersion)
    }

    @Test
    fun `a version older than the newest is rejected as not newer`() {
        val library = SkillLibrary()
        library.importDocument(document(version = "1.2.0"), nowMillis = 1000L)

        val outcome = library.importDocument(document(version = "1.0.0"), nowMillis = 2000L)

        assertTrue((outcome as SkillImportOutcome.Rejected).reason.contains("not newer"))
        assertEquals("1.2.0", library.packages().single().activeVersion)
    }

    @Test
    fun `versions compare as numbers, so 1_10_0 is newer than 1_9_0`() {
        val library = SkillLibrary()
        library.importDocument(document(version = "1.9.0"), nowMillis = 1000L)

        val outcome = library.importDocument(document(version = "1.10.0"), nowMillis = 2000L)

        assertTrue(outcome is SkillImportOutcome.Imported)
        assertEquals("1.10.0", library.packages().single().draftVersion)
    }

    @Test
    fun `front matter capabilities reach the active skill`() {
        val library = SkillLibrary()
        library.importDocument(
            document(
                author = "Ada",
                tools = "get_weather, web_fetch",
                permissions = "internet",
            ),
            nowMillis = 1000L,
        )

        val active = library.activeSkills().single()
        assertEquals(setOf("get_weather", "web_fetch"), active.tools)
        val version = library.packages().single().versions.single()
        assertEquals("Ada", version.author)
        assertEquals(setOf("internet"), version.permissions)
        assertEquals("an import is a user approval", SkillOrigin.USER, version.origin)
        assertTrue(version.approvedAtEpochMillis != null)
    }

    @Test
    fun `a malformed capabilities line is rejected rather than guessed at`() {
        val library = SkillLibrary()
        val outcome = library.importDocument(document(tools = "get_weather, not a tool!"), nowMillis = 1000L)

        assertTrue(outcome is SkillImportOutcome.Rejected)
        assertTrue((outcome as SkillImportOutcome.Rejected).reason.contains("tools line"))
    }

    @Test
    fun `a proposed version is marked agent-authored and unapproved`() {
        val library = SkillLibrary()
        library.proposeDraft(document(version = "0.1.0"), nowMillis = 1000L)

        val version = library.packages().single().versions.single()
        assertEquals(SkillOrigin.AGENT, version.origin)
        assertNull("an agent draft is approved only by activation", version.approvedAtEpochMillis)
    }

    @Test
    fun `disabling removes a skill from the prompt and enabling restores it`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)
        assertEquals(1, library.activeSkills().size)

        assertEquals(SkillActionOutcome.Ok, library.disable("weather-scout", nowMillis = 2000L))
        assertTrue("a disabled skill stays installed", library.packages().size == 1)
        assertTrue("a disabled skill leaves the prompt", library.activeSkills().isEmpty())
        assertEquals("disable keeps the active version", "1.0.0", library.packages().single().activeVersion)

        assertEquals(SkillActionOutcome.Ok, library.enable("weather-scout", nowMillis = 3000L))
        assertEquals(1, library.activeSkills().size)
    }

    @Test
    fun `activating with no draft fails`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)

        assertTrue(library.activateDraft("weather-scout", nowMillis = 2000L) is SkillActionOutcome.Failed)
    }

    @Test
    fun `importing a known version is rejected with where it already sits`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)
        library.importDocument(document(version = "1.1.0"), nowMillis = 2000L)

        val active = library.importDocument(document(version = "1.0.0"), nowMillis = 3000L)
        val draft = library.importDocument(document(version = "1.1.0"), nowMillis = 4000L)

        assertTrue((active as SkillImportOutcome.Rejected).reason.contains("already active"))
        assertTrue((draft as SkillImportOutcome.Rejected).reason.contains("already staged"))
    }

    @Test
    fun `remove drops the package`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)

        assertEquals(SkillActionOutcome.Ok, library.remove("weather-scout"))
        assertTrue(library.packages().isEmpty())
        assertTrue(library.remove("weather-scout") is SkillActionOutcome.Failed)
    }

    @Test
    fun `version history is capped without ever evicting the active version`() {
        val library = SkillLibrary()
        for (patch in 0 until 9) {
            library.importDocument(
                document(version = "1.0.$patch", description = "iteration $patch"),
                nowMillis = patch * 1000L,
            )
        }

        val pkg = library.packages().single()
        assertTrue(pkg.versions.size <= SkillDocument.MAX_KEPT_VERSIONS + 1)
        assertEquals("1.0.8", pkg.draftVersion)
        // Nine drafts must not have pushed the original active version out of the prompt.
        assertEquals("1.0.0", library.activeSkills().single().version)
    }

    @Test
    fun `validation rejects bad documents with a reason`() {
        val library = SkillLibrary()

        val noName = library.importDocument(document(name = ""), nowMillis = 1L)
        assertTrue((noName as SkillImportOutcome.Rejected).reason.contains("name"))

        val badVersion = library.importDocument(document(version = "v2"), nowMillis = 2L)
        assertTrue((badVersion as SkillImportOutcome.Rejected).reason.contains("version"))

        val noDescription = library.importDocument(document(description = ""), nowMillis = 3L)
        assertTrue((noDescription as SkillImportOutcome.Rejected).reason.contains("description"))

        val noBody = library.importDocument(document(instructions = ""), nowMillis = 4L)
        assertTrue((noBody as SkillImportOutcome.Rejected).reason.contains("instructions"))

        val noFrontMatter = library.importDocument("just some text", nowMillis = 5L)
        assertTrue((noFrontMatter as SkillImportOutcome.Rejected).reason.contains("front matter"))

        assertTrue(library.packages().isEmpty())
    }

    @Test
    fun `validation rejects an oversized document`() {
        val library = SkillLibrary()
        val oversized = document(instructions = "x".repeat(SkillDocument.MAX_SKILL_INSTRUCTIONS_CHARS + 1))

        val outcome = library.importDocument(oversized, nowMillis = 1L)

        assertTrue((outcome as SkillImportOutcome.Rejected).reason.contains("instructions"))
    }

    @Test
    fun `the prompt section names active skills and marks them untrusted`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)

        val prompt = SkillPrompt.append("You are Bram.", library.activeSkills())

        assertTrue(prompt.contains("You are Bram."))
        assertTrue(prompt.contains("ACTIVE SKILLS"))
        assertTrue(prompt.contains("Weather Scout (v1.0.0)"))
        assertTrue(prompt.contains("untrusted input"))
        // Progressive disclosure: the body loads through read_skill, never the prompt itself.
        assertTrue(!prompt.contains("Before any outdoor plan"))
        assertTrue(prompt.contains("read_skill"))
    }

    @Test
    fun `the prompt section is a no-op when nothing is active`() {
        val library = SkillLibrary()
        assertEquals("You are Bram.", SkillPrompt.append("You are Bram.", library.activeSkills()))
    }

    @Test
    fun `drafts are never rendered into the prompt`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)
        library.importDocument(
            document(version = "2.0.0", instructions = "draft-only procedure"),
            nowMillis = 2000L,
        )

        val prompt = SkillPrompt.append("base", library.activeSkills())

        assertTrue(prompt.contains("v1.0.0"))
        assertTrue(!prompt.contains("draft-only procedure"))
    }

    @Test
    fun `a proposed new skill lands as a draft with no active version`() {
        val library = SkillLibrary()
        val outcome = library.proposeDraft(document(), nowMillis = 1000L)

        assertEquals(SkillImportOutcome.Imported("weather-scout", "1.0.0", stagedAsDraft = true), outcome)
        val pkg = library.packages().single()
        assertNull(pkg.activeVersion)
        assertEquals("1.0.0", pkg.draftVersion)
        // A draft-only package is kept out of the prompt until it is activated.
        assertTrue(library.activeSkills().isEmpty())
    }

    @Test
    fun `a proposed new version of a known skill stages as a draft like import`() {
        val library = SkillLibrary()
        library.importDocument(document(), nowMillis = 1000L)

        val outcome = library.proposeDraft(document(version = "1.2.0"), nowMillis = 2000L)

        assertEquals(SkillImportOutcome.Imported("weather-scout", "1.2.0", stagedAsDraft = true), outcome)
        val pkg = library.packages().single()
        assertEquals("1.0.0", pkg.activeVersion)
        assertEquals("1.2.0", pkg.draftVersion)
        assertEquals("1.0.0", library.activeSkills().single().version)
    }

    @Test
    fun `activating a proposed draft promotes it into the prompt`() {
        val library = SkillLibrary()
        library.proposeDraft(document(), nowMillis = 1000L)
        assertTrue(library.activeSkills().isEmpty())

        assertEquals(SkillActionOutcome.Ok, library.activateDraft("weather-scout", nowMillis = 2000L))

        val pkg = library.packages().single()
        assertEquals("1.0.0", pkg.activeVersion)
        assertNull(pkg.draftVersion)
        assertEquals("1.0.0", library.activeSkills().single().version)
    }

    @Test
    fun `proposing a version that is already staged is rejected`() {
        val library = SkillLibrary()
        library.proposeDraft(document(), nowMillis = 1000L)

        val outcome = library.proposeDraft(document(), nowMillis = 2000L)

        assertTrue(outcome is SkillImportOutcome.Rejected)
        // Nothing changed: still one version, still a draft, still out of the prompt.
        val pkg = library.packages().single()
        assertEquals(listOf("1.0.0"), pkg.versions.map { it.version })
        assertTrue(library.activeSkills().isEmpty())
    }

    @Test
    fun `the draft hint names the skill and description, and says it is not active`() {
        val prompt = SkillPrompt.appendDraftHint("base prompt", "Git helper", "how to use git")
        assertTrue("carries the base prompt", prompt.startsWith("base prompt"))
        assertTrue("names the draft", prompt.contains("Git helper"))
        assertTrue("includes the description", prompt.contains("how to use git"))
        assertTrue("says it is not active", prompt.contains("not active"))
        assertTrue("asks not to nag", prompt.contains("once"))
    }

    @Test
    fun `the draft hint stands alone when there is no base prompt`() {
        val prompt = SkillPrompt.appendDraftHint("", "Git helper", "how to use git")
        assertTrue("names the draft without a base prompt", prompt.contains("Git helper"))
        assertTrue("no leading blank section", !prompt.startsWith("\n"))
    }
}
