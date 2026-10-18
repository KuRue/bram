package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.RecipeSourceKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RecipeSourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets = File("src/main/assets/hardware-recipes/official-v1")
    private var index = File(assets, "index.json").readText()
    private val recipeFile = JSONObject(index).getJSONArray("recipes").getJSONObject(0).getString("file")
    private var recipe = File(assets, recipeFile).readBytes()
    private var offline = false
    private val signer = RecipeTestSigner()
    private val requests = mutableListOf<String>()
    private val url = "https://recipes.example/snapshot/index.json"

    private fun manager(directory: File = temporary.newFolder()): RecipeSources = RecipeSources(
        directory,
        { File(assets, it).readBytes() },
        RecipeDownload { request, _, _ ->
            requests += request
            if (offline) throw IOException("offline")
            when {
                request.endsWith("/index.json") -> index.toByteArray()
                request.endsWith("/index.signature.json") -> signer.sign(index.toByteArray())
                else -> recipe
            }
        },
    )

    private fun accept(store: RecipeSources, review: RecipeSourceReview) { store.accept(review, review.snapshot.signature!!.fingerprint) }

    private fun revision(value: String) { index = JSONObject(index).put("revision", value).toString() }
    private fun refused(message: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertNotNull("expected failure containing '$message'", failure)
        assertTrue("${failure?.message} should contain $message", failure!!.message.orEmpty().contains(message))
    }

    @Test fun `download is only a review and external official claims confer no trust`() {
        val store = manager()
        assertEquals(RecipeSourceKind.OFFICIAL, store.bundled().index.source.kind)
        val review = store.review(url)
        assertTrue(store.sources().isEmpty())
        assertEquals(RecipeSourceKind.EXTERNAL, review.snapshot.index.source.kind)
        assertEquals(listOf(url, "https://recipes.example/snapshot/index.signature.json", "https://recipes.example/snapshot/$recipeFile"), requests)
        accept(store, review)
        assertEquals(review.snapshot.pin, store.sources().single().current.pin)
    }

    @Test fun `accepted snapshots survive restart offline and rollback is also offline`() {
        val directory = temporary.newFolder()
        val store = manager(directory)
        val first = store.review(url)
        accept(store, first)
        revision("next")
        val second = store.review(url)
        assertEquals(first.snapshot.pin, store.sources().single().current.pin)
        accept(store, second)
        offline = true
        requests.clear()
        val restarted = manager(directory)
        assertEquals(second.snapshot.pin, restarted.sources().single().current.pin)
        restarted.rollback(url)
        assertEquals(first.snapshot.pin, restarted.sources().single().current.pin)
        restarted.rollback(url)
        assertEquals(second.snapshot.pin, restarted.sources().single().current.pin)
        assertTrue(requests.isEmpty())
    }

    @Test fun `failed refresh preserves both pin and rollback`() {
        val store = manager()
        accept(store, store.review(url))
        revision("next")
        accept(store, store.review(url))
        val before = store.sources()
        recipe = "tampered".toByteArray()
        refused("hash mismatch") { store.review(url) }
        assertEquals(before, store.sources())
        offline = true
        refused("offline") { store.review(url) }
        assertEquals(before, store.sources())
    }

    @Test fun `revision and source identity cannot be silently rewritten`() {
        val store = manager()
        accept(store, store.review(url))
        val original = index
        index = JSONObject(index).put("generatedAt", "2026-10-18").toString()
        refused("reused revision") { store.review(url) }
        index = JSONObject(original).apply {
            getJSONObject("source").put("id", "impostor")
        }.put("revision", "next").toString()
        refused("identity changed") { store.review(url) }
    }

    @Test fun `approving an old review cannot overwrite a newly accepted source`() {
        val store = manager()
        val first = store.review(url)
        revision("next")
        accept(store, store.review(url))
        refused("changed since review") { accept(store, first) }
        assertEquals("next", store.sources().single().current.index.revision)
    }

    @Test fun `identical refresh preserves previous snapshot`() {
        val store = manager()
        accept(store, store.review(url))
        revision("next")
        accept(store, store.review(url))
        val before = store.sources()
        accept(store, store.review(url))
        assertEquals(before, store.sources())
    }

    @Test fun `source removal cannot affect bundled recipes`() {
        val store = manager()
        accept(store, store.review(url))
        store.remove(url)
        assertTrue(store.sources().isEmpty())
        assertEquals(1, store.bundled().recipes.size)
    }

    @Test fun `too many sources are refused before making a request`() {
        val store = manager()
        repeat(8) { number -> accept(store, store.review("https://recipes.example/$number/index.json")) }
        requests.clear()
        refused("At most 8") { store.review("https://recipes.example/extra/index.json") }
        assertTrue(requests.isEmpty())
    }

    @Test fun `foreign URLs and traversal in recipe filenames are refused before fetching them`() {
        listOf("../secret.json", "https://evil.example/payload.json", "//evil.example/payload.json", "x%2fpayload.json", "x?token.json", "x\\payload.json", "folder/recipe.json").forEach { file ->
            val store = manager()
            val obj = JSONObject(File(assets, "index.json").readText())
            obj.getJSONArray("recipes").getJSONObject(0).put("file", file)
            index = obj.toString()
            requests.clear()
            assertTrue("must refuse $file", runCatching { store.review(url) }.isFailure)
            assertEquals(listOf(url, "https://recipes.example/snapshot/index.signature.json"), requests)
        }
    }

    @Test fun `oversized index and recipe bodies cannot be parsed or accepted`() {
        val store = manager()
        index = " ".repeat(256 * 1024 + 1)
        refused("Index is too large") { store.review(url) }
        index = File(assets, "index.json").readText()
        recipe = ByteArray(64 * 1024 + 1)
        refused("Recipe is too large") { store.review(url) }
        assertTrue(store.sources().isEmpty())
    }

    @Test fun `recipe identity must agree with its index even if the hash matches`() {
        val store = manager()
        recipe = JSONObject(String(recipe)).put("version", 2).toString().toByteArray()
        index = JSONObject(index).apply {
            getJSONArray("recipes").getJSONObject(0).put("sha256", recipeSha256(recipe))
        }.toString()
        refused("identity/version") { store.review(url) }
    }

    @Test fun `unknown fields are refused even when the content hash agrees`() {
        val store = manager()
        recipe = JSONObject(String(recipe)).put("script", "untrusted").toString().toByteArray()
        index = JSONObject(index).apply {
            getJSONArray("recipes").getJSONObject(0).put("sha256", recipeSha256(recipe))
        }.toString()
        refused("script") { store.review(url) }
    }

    @Test fun `corrupt cached source is reported without hiding other sources or bundle`() {
        val directory = temporary.newFolder()
        val store = manager(directory)
        accept(store, store.review(url))
        accept(store, store.review("https://recipes.example/other/index.json"))
        val file = directory.listFiles()!!.first()
        file.writeText("corrupted")
        val loaded = store.load()
        assertEquals(1, loaded.sources.size)
        assertNotNull(loaded.bundled)
        assertNotNull(loaded.error)
        assertEquals("corrupted", file.readText())
    }

    @Test fun `cached index bytes are rechecked on restart`() {
        val directory = temporary.newFolder()
        val store = manager(directory)
        accept(store, store.review(url))
        val file = directory.listFiles()!!.single()
        val obj = JSONObject(file.readText())
        val current = obj.getJSONObject("current")
        current.put("index", JSONObject(current.getString("index")).put("revision", "tampered").toString())
        file.writeText(obj.toString())
        refused("index hash mismatch") { store.sources() }
    }

    @Test fun `GitHub shorthand requires an immutable full commit and HTTP indexes are refused`() {
        assertEquals("https://raw.githubusercontent.com/owner/recipes/${"a".repeat(40)}/index.json", RecipeSources.normalizeUrl("owner/recipes@${"a".repeat(40)}"))
        listOf("owner/recipes@main", "owner/recipes@aabbcc", "http://example.org/index.json", "https://user:pass@example.org/index.json", "https://example.org:8443/index.json", "https://example.org/index.json?key=secret", "https://example.org/index.json#x", "https://example.org/a/../index.json").forEach {
            assertTrue("must refuse $it", runCatching { RecipeSources.normalizeUrl(it) }.isFailure)
        }
    }
}
