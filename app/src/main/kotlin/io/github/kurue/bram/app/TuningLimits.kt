package io.github.kurue.bram.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Shared bounds for reference acquisition and a complete tuning pass, independent of Android. */
internal object TuningLimits {
    const val RUN_MILLIS = 10 * 60_000L
    const val REFERENCE_ATTEMPTS = 3
    const val COOLDOWN_MILLIS = 3 * 60_000L

    // The short-reference allowance includes 256 prompt tokens. Padded workloads need a larger
    // allowance; their prefill cost must not be mistaken for evidence of thermal throttling.
    fun slowReferenceMillis(padTokens: Int): Long =
        30_000L * (1 + (padTokens.coerceAtLeast(0).toLong() + 255) / 256)

    fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    suspend fun <T : Any> reference(
        attempt: suspend () -> T?,
        beforeRetry: suspend (nextAttempt: Int) -> Unit,
    ): T {
        repeat(REFERENCE_ATTEMPTS) { index ->
            currentCoroutineContext().ensureActive()
            attempt()?.let { return it }
            if (index + 1 < REFERENCE_ATTEMPTS) beforeRetry(index + 2)
        }
        error("CPU reference could not be measured after $REFERENCE_ATTEMPTS attempts; previous settings kept.")
    }
}

/** Retains ownership through native cleanup, so Cancel cannot overlap a replacement run. */
internal class TuningRunControl(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = TuningLimits.RUN_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onStopped: suspend (message: String) -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null

    fun inFlight(): Boolean = synchronized(lock) { job?.isCompleted == false }

    fun start(onCompleted: () -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Boolean = synchronized(lock) {
        if (job?.isCompleted == false) return false
        var completed = false
        lateinit var next: Job
        next = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                withTimeout(timeoutMillis, block)
                completed = true
            } catch (error: CancellationException) {
                val message = if (error is TimeoutCancellationException) {
                    "Configuration stopped at its time limit. Saved profile settings were kept. Reload the model to continue."
                } else {
                    "Configuration cancelled. Saved profile settings were kept. Reload the model to continue."
                }
                withContext(NonCancellable) { onStopped(message) }
                throw error
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    onStopped("Configuration stopped: ${error.message ?: error::class.simpleName}")
                }
            } finally {
                synchronized(lock) { if (job === next) job = null }
            }
        }
        next.invokeOnCompletion { cause -> if (cause == null && completed) onCompleted() }
        job = next
        next.start()
        true
    }

    fun cancel() = synchronized(lock) { job?.cancel() }
}
