package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.os.Build
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.MainViewModel
import io.github.kurue.bram.app.RecipeSources
import io.github.kurue.bram.app.RecipeDownload
import io.github.kurue.bram.app.recipePreviews
import io.github.kurue.bram.core.domain.AcceleratorAgreement
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in real JNI check using the generated, sub-megabyte procedural model; no model download. */
class RecipeNativeReplayOnDeviceTest {
    @Test fun applyACompleteRecipeThroughTheRealViewModelAndStores() = runBlocking<Unit> {
        val path = InstrumentationRegistry.getArguments().getString("recipeFixture").orEmpty()
        assumeTrue("Pass -e recipeFixture with an app-private generated fixture GGUF", path.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<BramApplication>()
        // This test owns the separate suffix's fresh catalog and an explicitly accepted test source.
        val container = app.container
        val imported = container.localModelStore.importModel(Uri.fromFile(File(path)))
        val profile = io.github.kurue.bram.core.domain.ModelProfile("recipe-native-application", "Native application fixture",
            imported.id, 512, threads = 1, decodeThreads = 1, batchTokens = 1, ubatchTokens = 1, poll = 0)
        container.modelProfileStore.save(profile)
        val sourceUrl = "https://native-fixture.example/index.json"
        val source = RecipeSources(File(app.filesDir, "recipe-sources"), { name ->
            app.assets.open("hardware-recipes/official-v1/$name").use { it.readBytes() }
        })
        val seed = source.bundled()
        val fixture = JSONObject(seed.documents.values.single()).apply {
            put("id", "native-fixture-batch")
            put("maintainer", "Bram native fixture test")
            put("match", JSONObject().put("manufacturers", JSONArray().put(Build.MANUFACTURER))
                .put("models", JSONArray().put(Build.MODEL))
                .put("socs", JSONArray().put(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE))
                .put("requireCpuFeatures", JSONArray()).put("minAndroidApi", 29))
            put("model", JSONObject().put("architectures", JSONArray().put(imported.architecture))
                .put("quantizations", JSONArray().put(imported.quantization)).put("sha256", imported.sha256)
                .put("sizeBytes", imported.fileSizeBytes).put("minContextTokens", 512))
            put("workload", JSONObject().put("promptTokens", 128).put("decodeTokens", 24)
                .put("contextDepth", 128).put("preference", "chat"))
            put("proposal", JSONObject().put("batchTokens", 128).put("ubatchTokens", 128))
            put("evidence", JSONObject().put("method", "Procedural protocol fixture; not language-model performance evidence"))
        }.toString().toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        val index = JSONObject(seed.indexJson).apply {
            put("source", JSONObject().put("id", "bram.native-fixture").put("name", "Native fixture source").put("kind", "external"))
            put("revision", "native-test-1")
            put("recipes", JSONArray().put(JSONObject().put("id", "native-fixture-batch").put("version", 1)
                .put("file", "recipe.json").put("sha256", digest)))
        }.toString().toByteArray()
        val signer = RecipeTestSigner()
        val reviewed = RecipeSources(File(app.filesDir, "recipe-sources"), { name ->
            app.assets.open("hardware-recipes/official-v1/$name").use { it.readBytes() }
        }, RecipeDownload { url, _, _ -> when {
            url.endsWith("/index.json") -> index
            url.endsWith("/index.signature.json") -> signer.sign(index)
            else -> fixture
        } })
        val review = reviewed.review(sourceUrl)
        reviewed.accept(review, review.snapshot.signature!!.fingerprint)
        val holder = ViewModelStore()
        lateinit var vm: MainViewModel
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm = ViewModelProvider(holder, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
                })[MainViewModel::class.java]
            }
            val ready = withTimeout(30_000) { vm.state.first { it.catalogsLoaded && it.recipeRuntimeAvailable &&
                !it.recipeSources.busy && !it.isLoadingModel && it.profiles.any { candidate -> candidate.id == profile.id } } }
            val preview = recipePreviews(ready, profile.id).single()
            assertNull(preview.blocked)
            assertEquals(1, container.modelProfileStore.find(profile.id)!!.batchTokens)
            var validationJobs = emptyList<Job>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val parent = vm.viewModelScope.coroutineContext[Job]!!
                val before = parent.children.toSet()
                vm.validateRecipe(profile.id, preview.key)
                validationJobs = parent.children.filterNot { it in before }.toList()
            }
            assertTrue("validation must start", validationJobs.isNotEmpty())
            withTimeout(90_000) { validationJobs.joinAll() }
            val finished = vm.state.value
            val saved = container.modelProfileStore.find(profile.id)!!
            assertEquals("${finished.error}; ${finished.autoConfigure?.current}", 128, saved.batchTokens)
            assertEquals(128, saved.ubatchTokens)
            assertTrue(saved.recipeProvenance.contains(sourceUrl))
            assertTrue(saved.recipeProvenance.startsWith("External:"))
            assertEquals(io.github.kurue.bram.core.domain.RecipeVerdict.APPLIED,
                container.recipeValidationHistory.read().first { it.key == preview.key }.verdict)
            android.util.Log.i("BramRecipeNativeTest", "Real ViewModel: paired validation applied batch 1/1 -> 128/128 with source provenance")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.clear() }
            reviewed.remove(sourceUrl)
            container.modelProfileStore.delete(profile.id)
            container.localModelStore.remove(imported.id)
        }
    }

    @Test fun profileReplayUsesChatThreadsAndPoolsWhileTheReferenceStaysFixed() = runBlocking<Unit> {
        val path = InstrumentationRegistry.getArguments().getString("recipeFixture").orEmpty()
        assumeTrue("Pass -e recipeFixture with an app-private generated fixture GGUF", path.isNotBlank())
        val app = ApplicationProvider.getApplicationContext<BramApplication>()
        val file = File(path)
        assertTrue("fixture must be readable by the app", file.isFile && file.canRead())
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val model = LocalModelRecord(ModelId("recipe-native-fixture"), "Procedural native fixture", file.name,
            "", file.path, file.length(), hash, ggufVersion = 3, architecture = "llama", quantization = "F32",
            trainedContextTokens = 512, layerCount = 1, hasChatTemplate = true)
        val client = LlamaCppServiceClient(app)
        fun JSONObject.tokens(name: String): List<Int> = getJSONArray(name).let { array -> (0 until array.length()).map(array::getInt) }
        try {
            withTimeout(60_000) {
                assertTrue(client.load(model, threads = 2, decodeThreads = 1, poll = 0).getBoolean("cpuValidated"))
                val reference = client.referenceDecode(24, 128).tokens("tokens")
                assertEquals(24, reference.size)
                val canonical = client.teacherForced(reference.toIntArray(), 128)
                assertFalse(canonical.getBoolean("profileThreads"))
                assertEquals(2, canonical.getInt("decodeThreads"))
                assertFalse(canonical.getBoolean("customThreadpool"))
                val trial = client.teacherForced(reference.toIntArray(), 128, useProfileThreads = true)
                assertTrue(trial.getBoolean("profileThreads"))
                assertEquals(2, trial.getInt("threads"))
                assertEquals(1, trial.getInt("decodeThreads"))
                assertTrue(trial.getBoolean("customThreadpool"))
                val predictions = trial.tokens("predictions")
                assertEquals(reference.size, predictions.size)
                assertTrue(AcceleratorAgreement.isUsable(AcceleratorAgreement.score(reference, predictions)))
                android.util.Log.i("BramRecipeNativeTest", "Profile replay: full CPU agreement, prompt=2/decode=1, custom pool applied")
            }
        } finally { runCatching { client.unload() } }
    }
}
