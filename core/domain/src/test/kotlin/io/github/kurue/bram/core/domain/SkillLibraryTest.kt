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
    ) = """
        ---
        name: $name
        version: $version
        description: $description
        ---
        $instructions
    """.trimIndent()

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
        assertTrue(prompt.contains("Before any outdoor plan"))
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
}
