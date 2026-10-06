package io.github.kurue.bram.runtime.llamacpp.inference

import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM has no `libbram_llama.so`, which makes it the cheapest place to prove the rule that cost
 * the `:inference` process its life: a library that will not load has to be reported, reported
 * once, and never thrown past a binder thread.
 */
class NativeBackendTest {
    private val dlopenFailure = UnsatisfiedLinkError("dlopen failed: library \"libbram_llama.so\" not found")

    private fun missingLibrary(onLoad: () -> Unit = {}): NativeBackend<String> =
        NativeBackend("x86_64") {
            onLoad()
            throw dlopenFailure
        }

    @Test
    fun `a library that loads is handed back, and loaded once`() {
        val attempts = AtomicInteger()
        val loaded = "the bridge"
        val backend = NativeBackend("arm64-v8a") { attempts.incrementAndGet(); loaded }

        assertTrue(backend.available)
        assertNull(backend.unavailableReason)
        repeat(3) { assertSame(loaded, backend.bridge()) }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `a library that will not load is reported, not thrown`() {
        val backend = missingLibrary()
        assertFalse(backend.available)

        val reason = backend.unavailableReason
        // The ABI is what a device-adaptation report needs, and the loader's own words are what
        // distinguish "not packaged" from "packaged but unloadable".
        assertTrue("reason should name the ABI, was: $reason", reason.orEmpty().contains("x86_64"))
        assertTrue(
            "reason should carry the loader's words, was: $reason",
            reason.orEmpty().contains("libbram_llama.so"),
        )

        // The point of the type: a fault, not an Error, so a caller can catch it and keep serving.
        val thrown = runCatching { backend.bridge() }.exceptionOrNull()
        assertTrue("was ${thrown?.let { it::class.simpleName }}", thrown is NativeBackendUnavailable)
        assertEquals(reason, (thrown as NativeBackendUnavailable).reason)
    }

    @Test
    fun `the load is attempted once, however many calls arrive`() {
        // The crash loop was a fresh process every 76 ms, each retrying the same doomed dlopen.
        val attempts = AtomicInteger()
        val backend = missingLibrary { attempts.incrementAndGet() }
        repeat(5) {
            runCatching { backend.bridge() }
            backend.unavailableJson()
            backend.unavailableEvent()
            backend.unavailableReason
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `the same reason comes back every time`() {
        val backend = missingLibrary()
        assertEquals(1, (1..3).map { backend.unavailableReason }.distinct().size)
    }

    @Test
    fun `a class-init failure still carries the loader's own words`() {
        // What `NativeLlamaBridge()` actually throws: loadLibrary runs in the companion init, so
        // the UnsatisfiedLinkError arrives wrapped and would otherwise be reported as
        // "ExceptionInInitializerError".
        val backend = NativeBackend<String>("x86_64") {
            throw ExceptionInInitializerError(dlopenFailure)
        }
        val reason = backend.unavailableReason.orEmpty()
        assertTrue("reason should name the ABI, was: $reason", reason.contains("x86_64"))
        assertTrue("reason should carry the loader's words, was: $reason", reason.contains("libbram_llama.so"))
    }

    @Test
    fun `a cpu without the required instructions keeps its own sentence`() {
        // CpuRequirements.check words a reason for the user; a generic wrapper would throw away the
        // only sentence that says what to do about it.
        val sentence = "This phone's processor lacks instructions Bram's on-device models need."
        val backend = NativeBackend("arm64-v8a") { throw IllegalStateException(sentence) }
        assertEquals(sentence, backend.unavailableReason)
    }

    @Test
    fun `the answer a call gives is json every caller can read`() {
        val backend = missingLibrary()
        val json = JSONObject(backend.unavailableJson().toString())
        // LlamaCppRuntime turns this key into "Native CPU runtime unavailable", with the reason as
        // its detail.
        assertFalse(json.optBoolean("nativeRuntimeLinked"))
        assertFalse(json.optBoolean("backendAvailable"))
        assertEquals(backend.unavailableReason, json.optString("unavailableReason"))
        // A backend list built from this must come out CPU-only, not a null dereference.
        assertEquals(0, json.optJSONArray("devices")?.length())
    }

    @Test
    fun `generate is told in kind, because a one-way callback cannot be handed an exception`() {
        val backend = missingLibrary()
        val event = JSONObject(backend.unavailableEvent().toString())
        assertEquals("failed", event.optString("type"))
        assertEquals(backend.unavailableReason, event.optString("message"))
        // A retry cannot produce the library, so it must not be offered.
        assertFalse(event.optBoolean("recoverable"))
    }

    @Test
    fun `a failure with nothing to say still names itself`() {
        val backend = NativeBackend<String>("arm64-v8a") { throw RuntimeException() }
        assertEquals("RuntimeException", backend.unavailableReason)
    }
}
