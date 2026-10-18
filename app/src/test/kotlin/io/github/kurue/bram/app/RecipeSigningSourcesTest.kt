package io.github.kurue.bram.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RecipeSigningSourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets = File("src/main/assets/hardware-recipes/official-v1")
    private var index = File(assets, "index.json").readBytes()
    private var signer = RecipeTestSigner()
    private var proof: ByteArray? = null
    private var missingProof = false
    private val requests = mutableListOf<String>()
    private val url = "https://recipes.example/index.json"
    private val directory by lazy { temporary.newFolder() }
    private fun store() = RecipeSources(directory, { File(assets, it).readBytes() }, RecipeDownload { request, _, _ ->
        requests += request
        when {
            request.endsWith("/index.json") -> index
            request.endsWith("/index.signature.json") -> {
                if (missingProof) throw IOException("signature not found")
                proof ?: signer.sign(index)
            }
            else -> File(assets, request.substringAfterLast('/')).readBytes()
        }
    })
    private fun accept(store: RecipeSources, review: RecipeSourceReview = store.review(url)) =
        store.accept(review, review.snapshot.signature!!.fingerprint)
    private fun revision(value: String) { index = JSONObject(String(index)).put("revision", value).toString().toByteArray() }
    private fun refused(message: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertNotNull("expected failure containing $message", failure)
        assertTrue("${failure?.message}", failure!!.message.orEmpty().contains(message))
    }

    @Test fun `first use needs approval of the exact fingerprint and never trusts a claimed official source`() {
        val store = store()
        val review = store.review(url)
        assertTrue(review.needsKeyApproval)
        assertFalse(review.changesKey)
        refused("Explicit approval") { store.accept(review) }
        refused("Explicit approval") { store.accept(review, "0".repeat(64)) }
        assertTrue(store.sources().isEmpty())
        accept(store, review)
        val saved = store.sources().single()
        assertEquals(review.snapshot.signature!!.publicKey, saved.pinnedKey)
        assertEquals(io.github.kurue.bram.core.domain.RecipeSourceKind.EXTERNAL, saved.current.index.source.kind)
        assertFalse(store.review(url).needsKeyApproval)
    }

    @Test fun `tampering exact index bytes fails before any recipe download and keeps accepted state`() {
        val store = store()
        accept(store)
        val before = store.sources()
        proof = signer.sign(index)
        index += '\n'.code.toByte()
        requests.clear()
        refused("signature is invalid") { store.review(url) }
        assertEquals(2, requests.size)
        assertEquals(before, store.sources())
    }

    @Test fun `missing malformed or oversized signatures never fall back to unsigned download`() {
        val store = store()
        accept(store)
        val before = store.sources()
        missingProof = true
        refused("signature not found") { store.review(url) }
        missingProof = false
        listOf("{}".toByteArray(), ByteArray(4097), "[]".toByteArray()).forEach {
            proof = it
            assertTrue(runCatching { store.review(url) }.isFailure)
            assertEquals(before, store.sources())
        }
    }

    @Test fun `same key update needs snapshot approval but no key replacement approval`() {
        val store = store()
        accept(store)
        revision("second")
        val review = store.review(url)
        assertFalse(review.needsKeyApproval)
        assertEquals(review.expectedPin, store.sources().single().current.pin)
        store.accept(review)
        assertEquals("second", store.sources().single().current.index.revision)
        val restarted = RecipeSources(directory, { File(assets, it).readBytes() }, RecipeDownload { _, _, _ -> error("offline") })
        assertEquals("second", restarted.sources().single().current.index.revision)
        restarted.rollback(url)
        assertEquals(review.expectedPin, restarted.sources().single().current.pin)
        assertEquals(review.expectedKey, restarted.sources().single().pinnedKey)
    }

    @Test fun `a changed key on identical index bytes cannot silently replace trust or roll it back`() {
        val store = store()
        accept(store)
        val old = store.sources().single()
        signer = RecipeTestSigner()
        val review = store.review(url)
        assertEquals(old.current.pin, review.snapshot.pin)
        assertTrue(review.changesKey)
        refused("Explicit approval") { store.accept(review) }
        assertEquals(old, store.sources().single())
        accept(store, review)
        val saved = store.sources().single()
        assertNotEquals(old.pinnedKey, saved.pinnedKey)
        assertFalse(saved.canRollback)
        refused("requires a new key review") { store.rollback(url) }
        assertEquals(saved, store.sources().single())
    }

    @Test fun `old reviews cannot undo key rotation rollback or remove and readd even at identical pins`() {
        val store = store()
        accept(store)
        val beforeRotation = store.review(url)
        signer = RecipeTestSigner()
        accept(store)
        refused("changed since review") { accept(store, beforeRotation) }
        revision("second")
        accept(store)
        val beforeRollback = store.review(url)
        store.rollback(url)
        store.rollback(url)
        refused("changed since review") { store.accept(beforeRollback) }
        val beforeRemove = store.review(url)
        store.remove(url)
        accept(store)
        refused("changed since review") { store.accept(beforeRemove) }
    }

    @Test fun `a validly signed older index is refused as an update and only Restore goes back`() {
        val store = store()
        val original = index
        accept(store)
        index = JSONObject(String(original)).put("revision", "second").put("generatedAt", "2026-10-18T09:30:00Z").toString().toByteArray()
        accept(store)
        val pinned = store.sources().single()
        index = original
        refused("older than the pinned snapshot") { store.review(url) }
        index = JSONObject(String(original)).put("revision", "stale").put("generatedAt", "2026-10-18T09:29:59Z").toString().toByteArray()
        refused("older than the pinned snapshot") { store.review(url) }
        assertEquals(pinned, store.sources().single())
        index = JSONObject(String(original)).put("revision", "same-day").put("generatedAt", "2026-10-18").toString().toByteArray()
        assertEquals("same-day", store.review(url).snapshot.index.revision)
        store.rollback(url)
        assertEquals(JSONObject(String(original)).getString("revision"), store.sources().single().current.index.revision)
    }

    @Test fun `accept revalidates proof and source identity at the write boundary`() {
        val store = store()
        val review = store.review(url)
        val altered = review.copy(snapshot = review.snapshot.copy(indexJson = review.snapshot.indexJson + "\n"))
        refused("signature is invalid") { accept(store, altered) }
        val unsigned = review.copy(snapshot = review.snapshot.copy(signature = null))
        refused("signed index") { store.accept(unsigned) }
        assertTrue(store.sources().isEmpty())
    }

    @Test fun `cache rechecks signatures even when an attacker recomputes the content hash`() {
        val store = store()
        accept(store)
        val file = directory.listFiles()!!.single()
        val cache = JSONObject(file.readText())
        val current = cache.getJSONObject("current")
        val altered = current.getString("index") + "\n"
        current.put("index", altered).put("pin", recipeSha256(altered.toByteArray()))
        file.writeText(cache.toString())
        refused("signature is invalid") { store.sources() }
        assertTrue(store.load().sources.isEmpty())
        assertNotNull(store.load().bundled)
    }

    @Test fun `legacy caches stay offline usable but upgrade requires key approval and cannot restore unsigned bytes`() {
        val store = store()
        accept(store)
        val file = directory.listFiles()!!.single()
        val legacy = JSONObject(file.readText()).put("version", 1)
        legacy.remove("pinnedKey")
        legacy.remove("stateId")
        legacy.getJSONObject("current").remove("signature")
        file.writeText(legacy.toString())
        val loaded = RecipeSources(directory, { File(assets, it).readBytes() }).sources().single()
        assertNull(loaded.pinnedKey)
        assertNull(loaded.current.signature)
        val review = store.review(url)
        assertTrue(review.needsKeyApproval)
        refused("Explicit approval") { store.accept(review) }
        accept(store, review)
        assertEquals(2, JSONObject(file.readText()).getInt("version"))
        assertFalse(store.sources().single().canRollback)
        refused("requires a new key review") { store.rollback(url) }
    }
}
