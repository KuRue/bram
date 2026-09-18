package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ActiveSkill
import io.github.kurue.bram.core.domain.SkillActionOutcome
import io.github.kurue.bram.core.domain.SkillImportOutcome
import io.github.kurue.bram.core.domain.SkillLibrary
import io.github.kurue.bram.core.domain.SkillPackage
import io.github.kurue.bram.core.domain.SkillStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read side of the skill loop, pinned against the pure library (the persistent store is a
 * Context-bound serialization detail tested on device).
 */
class SkillToolsTest {

    private class LibrarySkillStore : SkillStore {
        val library = SkillLibrary()
        private var now = 0L

        override suspend fun packages(): List<SkillPackage> = library.packages()

        override suspend fun activeSkills(): List<ActiveSkill> = library.activeSkills()

        override suspend fun importDocument(document: String): SkillImportOutcome =
            library.importDocument(document, ++now).also { persist() }

        override suspend fun proposeDraft(document: String): SkillImportOutcome =
            library.proposeDraft(document, ++now).also { persist() }

        override suspend fun activateDraft(skillId: String): SkillActionOutcome =
            library.activateDraft(skillId, ++now).also { persist() }

        override suspend fun rollback(skillId: String): SkillActionOutcome =
            library.rollback(skillId, ++now).also { persist() }

        override suspend fun disable(skillId: String): SkillActionOutcome =
            library.disable(skillId, ++now).also { persist() }

        override suspend fun enable(skillId: String): SkillActionOutcome =
            library.enable(skillId, ++now).also { persist() }

        override suspend fun remove(skillId: String): SkillActionOutcome = library.remove(skillId).also { persist() }

        private fun persist() = Unit
    }

    private fun skillDocument(
        name: String,
        version: String,
        description: String = "when to follow",
        body: String = "Step one. Step two.",
    ) = """
        ---
        name: $name
        version: $version
        description: $description
        ---
        $body
    """.trimIndent()

    private fun activeNames(json: String): List<String> {
        val array: JSONArray = JSONObject(json).getJSONArray("active")
        return (0 until array.length()).map { array.getJSONObject(it).getString("name") }
    }

    @Test
    fun `list_skills separates active skills from drafts`() = runBlocking {
        val store = LibrarySkillStore()
        store.importDocument(skillDocument("weather-scout", "1.0.0")) // active on import
        store.proposeDraft(skillDocument("weather-scout", "1.1.0")) // draft
        store.proposeDraft(skillDocument("git-helper", "0.1.0")) // never active

        val listed = JSONObject(ListSkillsTool(store).execute("{}"))
        assertEquals(listOf("weather-scout"), activeNames(listed.toString()))
        val drafts = listed.getJSONArray("drafts")
        // packages() is name-sorted, so git-helper precedes weather-scout.
        assertEquals(2, drafts.length())
        assertEquals("git-helper", drafts.getJSONObject(0).getString("name"))
        assertEquals("0.1.0", drafts.getJSONObject(0).getString("version"))
        assertEquals("weather-scout", drafts.getJSONObject(1).getString("name"))
        assertEquals("1.1.0", drafts.getJSONObject(1).getString("version"))
    }

    @Test
    fun `read_skill returns the active version's full instructions`() = runBlocking {
        val store = LibrarySkillStore()
        store.importDocument(skillDocument("weather-scout", "1.0.0"))
        store.proposeDraft(skillDocument("weather-scout", "1.1.0"))

        val read = JSONObject(ReadSkillTool(store).execute("""{"name":"Weather-Scout"}"""))
        assertEquals("1.0.0", read.getString("version"))
        assertTrue(read.getString("instructions").contains("Step one"))
    }

    @Test
    fun `read_skill chunks a long body and reports where to continue`() = runBlocking {
        val store = LibrarySkillStore()
        store.importDocument(skillDocument("long-skill", "1.0.0", body = "a".repeat(25_000)))

        val first = JSONObject(ReadSkillTool(store).execute("""{"name":"long-skill"}"""))
        assertEquals(20_000, first.getString("instructions").length)
        val nextOffset = first.getInt("nextOffset")
        assertEquals(20_000, nextOffset)

        val second = JSONObject(ReadSkillTool(store).execute("""{"name":"long-skill","offset":$nextOffset}"""))
        assertEquals(first.getInt("totalChars") - nextOffset, second.getString("instructions").length)
        assertFalse(second.has("nextOffset"))
    }

    @Test
    fun `read_skill refuses a draft-only skill rather than leaking unreviewed text`() = runBlocking {
        val store = LibrarySkillStore()
        store.proposeDraft(skillDocument("git-helper", "0.1.0"))

        val error = JSONObject(ReadSkillTool(store).execute("""{"name":"git-helper"}""")).getJSONObject("error")
        assertEquals("not_active", error.getString("code"))
    }

    @Test
    fun `read_skill names the skill when nothing matches`() = runBlocking {
        val error = JSONObject(ReadSkillTool(LibrarySkillStore()).execute("""{"name":"nope"}"""))
            .getJSONObject("error")
        assertEquals("not_found", error.getString("code"))
    }

    @Test
    fun `propose_skill says what the draft supersedes`() = runBlocking {
        val store = LibrarySkillStore()
        store.importDocument(skillDocument("weather-scout", "1.0.0"))

        val proposed = JSONObject(ProposeSkillTool(store).execute(proposalJson("weather-scout", "1.1.0")))
        assertTrue(proposed.getString("note").contains("Version 1.0.0 stays active"))
    }

    @Test
    fun `propose_skill marks a brand-new skill as new`() = runBlocking {
        val proposed = JSONObject(ProposeSkillTool(LibrarySkillStore()).execute(proposalJson("fresh", "1.0.0")))
        assertTrue(proposed.getString("note").contains("nothing is active yet"))
    }

    @Test
    fun `propose_skill points at read_skill for improving an existing one`() = runBlocking {
        val store = LibrarySkillStore()
        store.importDocument(skillDocument("weather-scout", "1.0.0"))
        val proposed = JSONObject(ProposeSkillTool(store).execute(proposalJson("weather-scout", "1.1.0")))
        assertTrue(proposed.getString("note").contains("read_skill"))
    }

    private fun proposalJson(name: String, version: String) = JSONObject()
        .put("name", name)
        .put("version", version)
        .put("description", "when to follow")
        .put("instructions", "New steps.")
        .toString()
}
