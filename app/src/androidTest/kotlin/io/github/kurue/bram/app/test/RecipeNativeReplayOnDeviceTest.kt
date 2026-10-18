package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.core.domain.AcceleratorAgreement
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in real JNI check using the generated, sub-megabyte procedural model; no model download. */
class RecipeNativeReplayOnDeviceTest {
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
