package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.McpServer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps MCP sessions warm between calls.
 *
 * A handshake is three small POSTs before every tool call otherwise, and a chain of calls pays it
 * each time. A session is cached per server for a short idle window: long enough that a run's
 * successive calls reuse it, short enough that a session the server has since dropped is not
 * resumed stale. The cached client also carries the token it was opened with, so reconfiguring a
 * server's credential never reuses the old one.
 *
 * A failed call drops the cached session but is not retried: the tool may already have run on the
 * server, and a retry could apply a side effect twice. The failure reaches the model, which can
 * decide; the next call gets a fresh handshake either way.
 *
 * Concurrency: read-only MCP calls can run in one batch, so each server's entry is guarded by a
 * mutex — two calls share one session, but never race the handshake or the cache.
 */
object McpSessions {

    private class Entry(
        val client: McpClient,
        val token: String?,
        var lastUsedAtMillis: Long,
    )

    private val mutex = Mutex()
    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun <T> withClient(server: McpServer, token: String?, block: suspend (McpClient) -> T): T {
        val cached = mutex.withLock {
            val entry = entries[server.id]
            if (entry != null && entry.token == token && !entry.expired()) {
                entry.lastUsedAtMillis = System.currentTimeMillis()
                entry
            } else {
                entries.remove(server.id)
                null
            }
        }
        if (cached != null) {
            try {
                return block(cached.client)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                mutex.withLock { entries.remove(server.id, cached) }
                throw failure
            }
        }

        val client = McpClient(server, token)
        client.connect()
        // Published only after the handshake succeeded, so a failed connect never leaves a
        // half-open client in the cache for the next call to trip over.
        mutex.withLock {
            entries[server.id] = Entry(client, token, System.currentTimeMillis())
        }
        return block(client)
    }

    /** Drops any cached session for a server, e.g. when its configuration changes. */
    suspend fun drop(serverId: String) {
        mutex.withLock { entries.remove(serverId) }
    }

    private fun Entry.expired(): Boolean =
        System.currentTimeMillis() - lastUsedAtMillis > IDLE_TIMEOUT_MILLIS

    private const val IDLE_TIMEOUT_MILLIS = 120_000L
}
