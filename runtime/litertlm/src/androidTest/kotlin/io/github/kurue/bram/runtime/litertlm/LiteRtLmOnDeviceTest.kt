package io.github.kurue.bram.runtime.litertlm

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.LiteRtBackend
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelId
import java.io.File
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads a real `.litertlm` package on the device and proves the engine produces text.
 *
 * This is the first on-device exercise of the litertlm runtime: it confirms the AAR's native
 * libraries load, a backend compiles a graph, and `sendMessageAsync` streams tokens back. The
 * model is pushed to the test app's external files dir before the run, so the test never depends
 * on a network or a content picker. The litertlm AAR ships only `arm64-v8a` natives, so the test
 * skips cleanly on the x86_64 emulator rather than failing to initialize the engine.
 *
 * IGNORED: litertlm AAR 0.15.0 (the latest release) crashes the process on every turn's completion.
 * Its compiled `Conversation.sendMessageAsync` bytecode calls `SendChannel.close$default`, a static
 * that no shipped kotlinx-coroutines provides (verified absent in 1.7.3 / 1.8.1 / 1.9.0 / 1.10.2),
 * so `onDone` throws NoSuchMethodError after generation finishes. The native libs load and the CPU
 * engine initializes and reaches `onDone` — i.e. inference runs — but no turn can complete. The GPU
 * path fails earlier with `embedding_lookup != nullptr` on a generic (non-device-matched) package.
 * Re-enable once a corrected AAR is released or the onDone teardown is patched.
 */
@Ignore("litertlm AAR 0.15.0 crashes on turn completion; see class kdoc")
@RunWith(AndroidJUnit4::class)
class LiteRtLmOnDeviceTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loadsAndGeneratesOnCpu() = runBlocking {
        assumeTrue("litertlm ships only arm64-v8a natives; skipping ${Build.SUPPORTED_ABIS.firstOrNull()}", Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")

        val pkg = File(ctx.getExternalFilesDir(null), "SmolLM2_135M_Instruct.litertlm")
        assertTrue("Missing test model at ${pkg.absolutePath}; adb push it before running.", pkg.exists())

        val record = LiteRtModelRecord(
            id = ModelId("litert:smoketest"),
            displayName = "SmolLM2 135M",
            fileName = pkg.name,
            contentUri = "",
            localPath = pkg.absolutePath,
            fileSizeBytes = pkg.length(),
            sha256 = "",
            backend = LiteRtBackend.CPU,
        )

        val manager = LiteRtEngineManager(ctx)
        try {
            manager.load(record)
            assertTrue("Engine reported not loaded after load()", manager.isLoaded)

            val request = GenerationRequest(
                messages = listOf(
                    ConversationMessage(role = MessageRole.USER, content = "Say hello in one short sentence.")
                ),
                maxOutputTokens = 48,
                requestId = "litert-smoke-1",
            )

            val events = withTimeout(240_000L) {
                manager.generate(request).take(20).toList()
            }

            val failure = events.filterIsInstance<GenerationEvent.Failed>().firstOrNull()
            if (failure != null) {
                val error = AssertionError("Generation failed: ${failure.message}")
                failure.cause?.let(error::initCause)
                throw error
            }
            val text = events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text }
            assertTrue("No text produced. Events seen: ${events.map { it::class.simpleName }}", text.isNotBlank())
        } finally {
            manager.unload()
        }
    }
}
