package io.github.kurue.bram.app

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a remote endpoint answered recently enough to be worth choosing again.
 *
 * Routing candidates used to be built with `available = true` unconditionally, so a dead server
 * was still picked, its turn failed, and the fallback ran — the user paid a round trip to a timeout
 * to learn what the last turn already knew. A failed remote turn now marks the endpoint down for a
 * short cooldown; the router skips it (the router already rejects unavailable candidates), and a
 * success — from any turn or a manual pick — clears it at once.
 *
 * Deliberately in-memory: reachability is about this session, not a stored judgment. The process
 * restarting is itself a reason to try again.
 */
object EndpointHealth {
    private val failedAtEpochMillis = ConcurrentHashMap<String, Long>()

    /** Overridable for tests; production uses the wall clock. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    fun markFailure(endpointId: String) {
        failedAtEpochMillis[endpointId] = clock()
    }

    fun markSuccess(endpointId: String) {
        failedAtEpochMillis.remove(endpointId)
    }

    fun isReachable(endpointId: String): Boolean {
        val failedAt = failedAtEpochMillis[endpointId] ?: return true
        return clock() - failedAt > FAILURE_COOLDOWN_MILLIS
    }

    private const val FAILURE_COOLDOWN_MILLIS = 60_000L
}
