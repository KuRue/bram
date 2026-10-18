package io.github.kurue.bram.app.test

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.BramTheme
import io.github.kurue.bram.app.RecipeDownload
import io.github.kurue.bram.app.RecipeSources
import io.github.kurue.bram.app.RecipeSourcesScreen
import io.github.kurue.bram.app.RecipeSignature
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Real Android cache replacement and Compose review/rollback, without a public network or model. */
class RecipeSourcesOnDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val application get() = ApplicationProvider.getApplicationContext<BramApplication>()
    private val directory by lazy { File(application.cacheDir, "recipe-source-test-${UUID.randomUUID()}") }
    private fun asset(name: String) = application.assets.open("hardware-recipes/official-v1/$name").use { it.readBytes() }

    @After fun cleanUp() { directory.deleteRecursively() }

    @Test fun reviewPinRefreshOfflineRollbackAndRemove() {
        var revision = "first"
        var offline = false
        val signer = RecipeTestSigner()
        fun index() = JSONObject(String(asset("index.json"))).put("revision", revision).toString().toByteArray()
        val store = RecipeSources(directory, ::asset, RecipeDownload { url, _, _ ->
            if (offline) throw IOException("Offline; pinned snapshot kept")
            if (url.endsWith("/index.json")) {
                index()
            } else if (url.endsWith("/index.signature.json")) signer.sign(index()) else asset(url.substringAfterLast('/'))
        })
        val state = mutableStateOf(store.load())
        fun action(work: () -> Unit) {
            try {
                work()
                state.value = store.load()
            } catch (failure: Exception) {
                state.value = state.value.copy(error = failure.message)
            }
        }
        compose.setContent {
            BramTheme {
                RecipeSourcesScreen(
                    state.value, onBack = {},
                    onReview = { url -> state.value = state.value.copy(review = store.review(url)) },
                    onAccept = { fingerprint -> action { store.accept(state.value.review!!, fingerprint) } },
                    onDismissReview = { state.value = state.value.copy(review = null) },
                    onRollback = { url -> action { store.rollback(url) } },
                    onRemove = { url -> action { store.remove(url) } },
                )
            }
        }
        compose.onNodeWithTag("recipe-source-url").performScrollTo().performTextInput(URL)
        compose.onNodeWithTag("recipe-source-review").performScrollTo().performClick()
        compose.onNodeWithText("Review · not active").performScrollTo().assertIsDisplayed()
        assertTrue(store.sources().isEmpty())
        compose.onNodeWithTag("recipe-source-accept").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("recipe-key-approve").performScrollTo().performClick()
        compose.onNodeWithTag("recipe-source-accept").performScrollTo().performClick()
        assertEquals("first", store.sources().single().current.index.revision)

        revision = "second"
        compose.onNodeWithTag("recipe-refresh").performScrollTo().performClick()
        assertEquals("first", store.sources().single().current.index.revision)
        compose.onNodeWithTag("recipe-source-accept").performScrollTo().performClick()
        assertEquals("second", store.sources().single().current.index.revision)

        offline = true
        // A new store reads the accepted snapshots from disk, without invoking the downloader.
        assertEquals("second", RecipeSources(directory, ::asset).sources().single().current.index.revision)
        compose.runOnIdle { action { store.review(URL) } }
        compose.onNodeWithText("Offline; pinned snapshot kept").performScrollTo().assertIsDisplayed()
        assertEquals("second", store.sources().single().current.index.revision)
        compose.onNodeWithText("Restore first").performScrollTo().performClick()
        assertEquals("first", store.sources().single().current.index.revision)

        compose.onNodeWithText("Remove source").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, store.sources().size)
        compose.onNodeWithText("Remove source").performScrollTo().performClick()
        compose.onNodeWithText("Remove", useUnmergedTree = true).performClick()
        assertTrue(store.sources().isEmpty())
        assertNotNull(state.value.bundled)
    }

    @Test fun changedKeyNeedsFreshApprovalAndCannotBeRestoredByRollback() {
        val index = asset("index.json")
        var signer = RecipeTestSigner()
        val store = RecipeSources(directory, ::asset, RecipeDownload { url, _, _ -> when {
            url.endsWith("/index.json") -> index
            url.endsWith("/index.signature.json") -> signer.sign(index)
            else -> asset(url.substringAfterLast('/'))
        } })
        val first = store.review(URL)
        store.accept(first, first.snapshot.signature!!.fingerprint)
        val pinnedKey = store.sources().single().pinnedKey
        signer = RecipeTestSigner()
        val rotated = store.review(URL)
        val state = mutableStateOf(store.load().copy(review = rotated))
        compose.setContent {
            BramTheme {
                RecipeSourcesScreen(state.value, onBack = {}, onReview = {},
                    onAccept = { fingerprint -> store.accept(state.value.review!!, fingerprint); state.value = store.load() },
                    onDismissReview = { state.value = state.value.copy(review = null) },
                    onRollback = { error("A retired key must never be restored without review") }, onRemove = {})
            }
        }
        compose.onNodeWithText("Signing key changed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recipe-source-accept").performScrollTo().assertIsNotEnabled()
        assertEquals(pinnedKey, store.sources().single().pinnedKey)
        compose.onNodeWithTag("recipe-key-approve").performScrollTo().performClick()
        compose.onNodeWithTag("recipe-source-accept").performScrollTo().performClick()
        assertNotEquals(pinnedKey, store.sources().single().pinnedKey)
        compose.onNodeWithText("Restore ${first.snapshot.index.revision}").performScrollTo().assertIsNotEnabled()
        val restarted = RecipeSources(directory, ::asset).sources().single()
        assertEquals(rotated.snapshot.signature!!.publicKey, restarted.pinnedKey)
        assertFalse(restarted.canRollback)
    }

    @Test fun publicOpenSslSignatureVerifiesWithTheAndroidProvider() {
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val index = assets.open("recipe-signing/index.json").use { it.readBytes() }
        val proof = assets.open("recipe-signing/index.signature.json").use { it.readBytes() }
        assertEquals("17c23896551d3644bbb81ff39ec56723f53ae73c046a5abbb9caec5655553b78",
            RecipeSignature.verify(index, proof).fingerprint)
        assertTrue(runCatching { RecipeSignature.verify(index + '\n'.code.toByte(), proof) }.isFailure)
    }

    private companion object { const val URL = "https://recipes.example/test/index.json" }
}
