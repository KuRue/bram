package io.github.kurue.bram.app.test

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MessageId
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `:inference` process must survive every call the binder surface accepts, whether or not the
 * native library is there.
 *
 * This is the regression test for a crash loop rather than for a return value. The library used to
 * be reached through a throwing `lazy`, so an absent one raised an `UnsatisfiedLinkError` *inside*
 * `onTransact` — after which nothing on the client side can help, because the process it was
 * talking to no longer exists. Measured on the emulator, which packages no x86_64 copy of
 * `libbram_llama.so`: `MainViewModel`'s ordinary startup `devices()` call killed `:inference` and a
 * fresh one started 76 ms later, twice, while the app itself stayed perfectly usable. The same
 * happens on a phone whose packaged copy is corrupt or built for another ABI.
 *
 * Both outcomes are asserted, so the same test is honest on an emulator (library missing) and on a
 * phone (library present): the invariant is that the process is still there afterwards, and only
 * the answer differs. [LlamaCppServiceClient.processFailureListener] is the proof of the invariant
 * — it fires from the client's binder death recipient, which no rebinding can hide.
 */
@RunWith(AndroidJUnit4::class)
class InferenceProcessSurvivalOnDeviceTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyBackendCallLeavesTheProcessStanding() = runBlocking {
        val client = LlamaCppServiceClient(ctx)
        val deaths = mutableListOf<String>()
        client.processFailureListener = { message -> deaths += message }
        try {
            // Three rounds, because the crash loop was only visible on the *second* call: the first
            // one died, the client rebound, and the second died too.
            repeat(3) { round ->
                val probe = withTimeout(30_000L) { client.probe() }
                val state = withTimeout(30_000L) { client.state() }
                val devices = withTimeout(30_000L) { client.devices() }
                // Counting needs a loaded model on a phone build, and this test loads none. The
                // assertion is that the call returns, not what it counted.
                runCatching { withTimeout(30_000L) { client.countTokens(listOf(hello())) } }
                // Every one of these is a separate binder method, and every one of them reaches
                // the library. A missing library has to answer each rather than take the process
                // down on the first.
                runCatching { withTimeout(30_000L) { client.referenceDecode(8) } }
                runCatching { withTimeout(30_000L) { client.teacherForced(intArrayOf(1, 2), 0) } }
                runCatching { withTimeout(30_000L) { client.parseReply("hello") } }
                runCatching { withTimeout(30_000L) { client.benchmark("pp", 8, 0, 1) } }
                runCatching { withTimeout(30_000L) { client.memoryBandwidth(16, 1) } }
                runCatching { withTimeout(30_000L) { client.quantize("/nope.gguf", "/nope-out.gguf", "Q4_K_M") } }
                runCatching { withTimeout(30_000L) { client.unload() } }
                runCatching { withTimeout(30_000L) { client.loadEmbedder("/nope.gguf", 1) } }
                // An embedding must come back as "none" so the memory store falls back to keyword
                // recall, rather than as a zero-length vector it would store as a real one.
                assertNull("round $round", client.embed("hello"))
                runCatching { withTimeout(30_000L) { client.unloadEmbedder() } }
                client.cancel("no-such-request")

                // A turn has to be told, not left hanging: generate is a one-way callback, so an
                // exception could never have reached it anyway.
                val events = withTimeout(30_000L) {
                    client.generate(GenerationRequest(messages = listOf(hello()), requestId = "survival-$round"))
                        .toList()
                }
                assertTrue("round $round produced no events: $events", events.isNotEmpty())

                if (!probe.optBoolean("nativeRuntimeLinked")) {
                    // The unavailable branch: a reason to show, a device list a caller can read,
                    // and no claim that something is loaded.
                    val reason = probe.optString("unavailableReason")
                    assertTrue(
                        "round $round: unavailable without a reason: $probe",
                        reason.contains("libbram_llama") || reason.contains("llama.cpp"),
                    )
                    assertFalse("$probe", probe.optBoolean("backendAvailable"))
                    assertEquals(0, devices.optJSONArray("devices")?.length())
                    // Recorded as failed, and not offered a retry: the library will not appear.
                    val failed = events.filterIsInstance<GenerationEvent.Failed>().singleOrNull()
                    assertTrue("round $round: expected one Failed, got $events", failed != null)
                    assertFalse("round $round: must not be recoverable", failed!!.recoverable)
                    assertEquals(probe.optString("unavailableReason"), failed.message)
                }

                // A death notice arrives on a binder thread, after the call that caused it has
                // already returned. Waiting it out is what makes an empty list mean "nobody died"
                // rather than "the notice has not landed yet".
                delay(500)
                assertTrue("round $round died: $deaths", deaths.isEmpty())
                val after = withTimeout(30_000L) { client.probe() }
                assertEquals(
                    "round $round: a different process answered, so the first one died",
                    probe.optString("unavailableReason"),
                    after.optString("unavailableReason"),
                )
            }
        } finally {
            client.close()
        }
    }

    /**
     * A death notice is delivered on a binder thread, so the assertion above can run before the
     * client has been told about a death that already happened. Given its own test, this waits out
     * that race: if a call did kill the process, the failure listener must end up recording it.
     */
    @Test
    fun aCallThatCannotBeServedDoesNotKillTheProcess() = runBlocking {
        val client = LlamaCppServiceClient(ctx)
        val deaths = mutableListOf<String>()
        client.processFailureListener = { message -> deaths += message }
        try {
            val probe = withTimeout(30_000L) { client.probe() }
            if (probe.optBoolean("nativeRuntimeLinked")) {
                // On a phone build the library is there, so there is nothing to assert here: the
                // test above covers the calls themselves.
                return@runBlocking
            }
            // The startup path that actually triggered the loop, called on its own.
            repeat(3) { withTimeout(30_000L) { client.devices() } }
            delay(1_000)
            assertTrue(":inference died while reporting an unavailable library: $deaths", deaths.isEmpty())
            // And still answering afterwards, as the unavailable report rather than as a crash.
            val after = JSONObject(withTimeout(30_000L) { client.probe() }.toString())
            assertFalse("$after", after.optBoolean("nativeRuntimeLinked"))
        } finally {
            client.close()
        }
    }

    private fun hello() = ConversationMessage(
        id = MessageId("survival-probe"),
        role = MessageRole.USER,
        content = "hello",
    )
}
