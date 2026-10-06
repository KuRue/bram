package io.github.kurue.bram.runtime.llamacpp.inference

import org.json.JSONArray
import org.json.JSONObject

/**
 * The native library is not here, so this process cannot serve a single request.
 *
 * Deliberately an [IllegalStateException] and not an [UnsatisfiedLinkError]: the reason a missing or
 * unloadable library must not be allowed to escape is that it is an `Error` raised inside a binder
 * method, and a throwable leaving `onTransact` takes the whole `:inference` process down. The client
 * answers a dead process by rebinding, so the next call loads nothing and dies again — measured on
 * the emulator, where the APK carries no x86_64 copy of the library, at 76 ms between starts.
 *
 * On a phone the same thing happens whenever the packaged copy is corrupt or was built for another
 * ABI, so this is not merely an emulator artefact.
 */
internal class NativeBackendUnavailable(val reason: String) : IllegalStateException(reason)

/**
 * The one place `libbram_llama.so` is loaded, and the record of whether it loaded.
 *
 * Two rules, both of them learned from a `:inference` process that crash-looped about twice a
 * second while the app sat there perfectly usable:
 *
 * 1. **Load once.** The attempt is remembered whether it succeeded or not, so a device without the
 *    library pays one `dlopen` and then answers every call from the record instead of retrying a
 *    load that cannot succeed.
 * 2. **Report, never throw past the binder.** [bridge] throws [NativeBackendUnavailable] for the
 *    caller's convenience inside this process; [InferenceProcessService] catches that one type at
 *    every binder entry point and returns [unavailableJson] instead. Anything else still propagates:
 *    a native call that fails with a model loaded is a different fault, and it reaches the client
 *    the way it always has.
 *
 * The loader is injected, and so is what it returns, so both rules are testable on the JVM where
 * `libbram_llama.so` cannot load at all.
 */
internal class NativeBackend<T : Any>(
    /** The device's primary ABI, named in the reason: an ABI mismatch is the usual cause. */
    private val abi: String,
    private val loader: () -> T,
) {
    // `runCatching` never throws, so `lazy` records a failure instead of re-running the load on
    // every access the way a throwing initializer would.
    private val attempt: Result<T> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching(loader)
    }

    val available: Boolean get() = attempt.isSuccess

    /** Why the library is unusable, or null when it loaded. Null once [bridge] has succeeded. */
    val unavailableReason: String? get() = attempt.exceptionOrNull()?.let(::describe)

    /**
     * The loaded library, or a [NativeBackendUnavailable] that carries the reason.
     *
     * Every call into the native side goes through here, which is what makes rule 2 total: there is
     * no other path to a library that is not loaded.
     */
    fun bridge(): T = attempt.fold(
        onSuccess = { it },
        onFailure = { throw NativeBackendUnavailable(describe(it)) },
    )

    /**
     * The answer every call gives while the library is missing, in the shape each caller already
     * reads. `nativeRuntimeLinked` is what [LlamaCppRuntime] turns into "Native CPU runtime
     * unavailable", and the empty `devices` array keeps a backend list a caller is building
     * CPU-only rather than a null-pointer. A client that ignored the key still gets valid JSON
     * instead of a dead process.
     */
    fun unavailableJson(): JSONObject = JSONObject()
        .put("backendAvailable", false)
        .put("nativeRuntimeLinked", false)
        .put("unavailableReason", unavailableReason)
        .put("devices", JSONArray())

    /** The `generate` equivalent of [unavailableJson]: a one-way callback can only be answered. */
    fun unavailableEvent(): JSONObject = JSONObject()
        .put("type", "failed")
        // Not recoverable: the library will not appear on the next attempt, so a retry would only
        // spend the user's time again.
        .put("recoverable", false)
        .put("message", unavailableReason)

    private fun describe(error: Throwable): String {
        // `System.loadLibrary` runs in `NativeLlamaBridge`'s static initialiser, so the failure a
        // caller actually catches is an ExceptionInInitializerError whose cause is the
        // UnsatisfiedLinkError. Reporting the wrapper would throw away the only sentence that says
        // the library is missing.
        val chain = generateSequence(error) { it.cause }.toList()
        val link = chain.filterIsInstance<UnsatisfiedLinkError>().firstOrNull()
        if (link != null) {
            return "This build has no llama.cpp library that can load on this device's " +
                "CPU ($abi): ${link.message ?: "the library would not load"}. " +
                "Local models need one; use a model on a server instead."
        }
        // CpuRequirements.check words its own reason for the user, and it throws this type too.
        val stated = chain.filterIsInstance<IllegalStateException>().firstOrNull()
        if (stated != null) {
            return stated.message?.takeIf(String::isNotBlank) ?: "On-device inference is unavailable."
        }
        val leaf = chain.last()
        return leaf.message?.takeIf(String::isNotBlank) ?: leaf::class.java.simpleName
    }
}
