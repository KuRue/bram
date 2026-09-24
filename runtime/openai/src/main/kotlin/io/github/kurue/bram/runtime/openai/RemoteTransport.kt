package io.github.kurue.bram.runtime.openai

import java.io.Closeable
import java.net.HttpURLConnection
import java.io.BufferedReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One remote response the runtime can read, whichever client opened it.
 *
 * The seam exists for two reasons. The OkHttp implementation is the migrated path, and it is the
 * one that can be cancelled: a coroutine cancellation closes the socket, so a stopped turn ends
 * immediately instead of waiting out the read timeout. The [LegacyHttpUrlConnectionResponse] keeps
 * the previous reader reachable behind [RemoteClientMode.useLegacyHttpUrlConnection] for one slice,
 * so a bad provider interaction is a flag rather than a dependency revert.
 */
internal interface RemoteResponse : Closeable {
    val statusCode: Int
    val isEventStream: Boolean

    /** Reads `data:` frames, calling [onData] for each; returning false stops reading. */
    suspend fun readSseLines(onData: suspend (String) -> Boolean)

    /** The whole body, for a server that answered one JSON document rather than a stream. */
    fun readWholeBody(): String
}

/**
 * Which reader the generation path uses.
 *
 * Defaults to OkHttp. The legacy flag is a rollback for a single slice and is removed once the
 * device suite and a real endpoint have passed; it must not become a permanent second code path.
 */
internal object RemoteClientMode {
    var useLegacyHttpUrlConnection: Boolean = false
}

/**
 * The migrated reader.
 *
 * OkHttp closes the socket when a [Call] is cancelled, so the watcher below is what finally lets a
 * cancellation reach a body read that is already parked in the socket — the thing
 * `HttpURLConnection` cannot do. Cancelling an already-finished call is harmless, so the watcher
 * needs no bookkeeping on the normal path.
 */
internal class OkHttpRemoteResponse(
    private val call: Call,
    private val response: Response,
) : RemoteResponse {

    override val statusCode: Int get() = response.code

    override val isEventStream: Boolean
        get() = response.header("Content-Type")?.lowercase()?.contains("text/event-stream") == true

    override suspend fun readSseLines(onData: suspend (String) -> Boolean) {
        val body = response.body ?: return
        // Read through `charStream()` rather than `source()`: with this OkHttp/Okio pair a
        // `source()` on a chunked response hands back an already-exhausted source (measured —
        // `charStream()` and `byteStream()` return the whole body, `source()` returns none),
        // and a BufferedReader is the same shape the previous reader used, so the frame parser
        // below is unchanged.
        body.charStream().buffered().use { reader ->
            val data = StringBuilder()
            while (true) {
                val line = reader.nextLine(call) ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val dispatch = onData(data.toString())
                            data.clear()
                            if (!dispatch) return@use
                        }
                    }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                    // `event:` names, comments (`:`), and other fields are not needed by either
                    // wire kind: the payload carries its own type.
                }
            }
            if (data.isNotEmpty()) onData(data.toString())
        }
    }

    override fun readWholeBody(): String = response.body?.string().orEmpty()

    override fun close() {
        response.close()
        call.cancel()
    }
}

/**
 * Awaits the response head, keeping the call cancellable.
 *
 * `invokeOnCancellation` is the hook that matters: it fires when cancellation is *requested*, not
 * when the coroutine finishes, so a cancelled turn can close the socket while the body read is still
 * parked in it.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        },
    )
    continuation.invokeOnCancellation { cancel() }
}

private val bodyReads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Reads one line, detaching from the read itself when the caller is cancelled.
 *
 * Only the blocking read is decoupled. The frame parsing and the `onData` emit stay in the caller's
 * own coroutine: a flow may only be emitted from its own context, and hoisting the emit breaks the
 * invariant, which is what silently emptied every streaming transcript in an earlier attempt.
 *
 * The read is launched outside the caller's scope because a coroutine that owns it cannot escape it
 * — `coroutineScope`, `withContext` and `select` all *join* a block rather than abandon it. On
 * cancellation the continuation resumes at once, so the turn settles without waiting for the socket,
 * and [call] is closed so the parked thread has somewhere to go.
 */
private suspend fun BufferedReader.nextLine(call: Call): String? =
    suspendCancellableCoroutine { continuation ->
        bodyReads.launch {
            runCatching { readLine() }.fold(
                onSuccess = { line -> if (continuation.isActive) continuation.resume(line) },
                onFailure = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                },
            )
        }
        continuation.invokeOnCancellation {
            // Explicit: an unqualified cancel() would hit the continuation, not the call.
            call.cancel()
        }
    }

/**
 * The reader being replaced, kept for one slice behind [RemoteClientMode].
 *
 * It is the behaviour this migration exists to fix: the read parks the calling thread in the socket
 * and cancelling the coroutine cannot interrupt it, so a stopped turn waits out the read timeout.
 */
internal class LegacyHttpUrlConnectionResponse(
    private val connection: HttpURLConnection,
) : RemoteResponse {

    override val statusCode: Int get() = connection.responseCode

    override val isEventStream: Boolean
        get() = connection.contentType?.lowercase()?.contains("text/event-stream") == true

    override suspend fun readSseLines(onData: suspend (String) -> Boolean) {
        connection.inputStream.bufferedReader().use { reader ->
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val dispatch = onData(data.toString())
                            data.clear()
                            if (!dispatch) return@use
                        }
                    }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                }
            }
            if (data.isNotEmpty()) onData(data.toString())
        }
    }

    override fun readWholeBody(): String = connection.inputStream.bufferedReader().use { it.readText() }

    override fun close() = connection.disconnect()
}
