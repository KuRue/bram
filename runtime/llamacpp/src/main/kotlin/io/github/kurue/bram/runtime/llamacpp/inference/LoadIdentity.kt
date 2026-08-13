package io.github.kurue.bram.runtime.llamacpp.inference

import io.github.kurue.bram.core.domain.canonicalCpuMask
import org.json.JSONObject

/**
 * The settings a load was built with, compared field-for-field so a context is never reused
 * for a different configuration: reusing one would silently run — or worse, validate — the
 * previous configuration. Pure so the reuse rule ("same identity means reuse, any difference
 * means reload") is unit-tested rather than trusted to a hand-rolled equality chain.
 */
data class LoadIdentity(
    val modelId: String,
    val contextTokens: Int,
    val gpuLayers: Int,
    val deviceFilter: String,
    val enableThinking: Boolean,
    val flashAttention: String,
    val kvCacheType: String,
    val batchTokens: Int,
    val ubatchTokens: Int,
    val threads: Int,
    val cpuMask: String,
    val cpuStrict: Boolean,
    val poll: Int,
    val threadPriority: String,
    val loadMode: String,
    val hexUseHmx: Boolean,
    val hexDisableNhvx: Boolean,
    val hexHostBuf: Boolean,
    val hexOpBatch: Int,
    val hexNDev: Int,
) {
    /** A stable string for the flag combination, so environment comparisons are exact. */
    val hexKey: String
        get() = "hmx=${if (hexUseHmx) 1 else 0}|nhvx=${if (hexDisableNhvx) 1 else 0}" +
            "|hb=${if (hexHostBuf) 1 else 0}|ob=$hexOpBatch|nd=$hexNDev"

    companion object {
        /**
         * Parses and normalizes a load request the way the service needs it, so "default" means
         * the same thing on every request: threads of zero or less become the device's core
         * count, batch values clamp to the context, and the mask is canonical lowercase hex.
         */
        fun from(request: JSONObject): LoadIdentity {
            val contextTokens = request.getInt("contextTokens").coerceAtLeast(256)
            val batchTokens = request.optInt("batchTokens", 0).coerceIn(0, contextTokens)
            val requestedThreads = request.optInt("threads", 0)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val cpuMask = canonicalCpuMask(request.optString("cpuMask"))
            return LoadIdentity(
                modelId = request.getString("modelId"),
                contextTokens = contextTokens,
                gpuLayers = request.optInt("gpuLayers", 0).coerceAtLeast(0),
                deviceFilter = request.optString("deviceFilter"),
                enableThinking = request.optBoolean("enableThinking", false),
                flashAttention = request.optString("flashAttention", "auto"),
                kvCacheType = request.optString("kvCacheType", "f16"),
                batchTokens = batchTokens,
                ubatchTokens = request.optInt("ubatchTokens", 0).coerceIn(0, batchTokens),
                threads = if (requestedThreads <= 0) cores else requestedThreads.coerceIn(1, cores),
                cpuMask = cpuMask,
                cpuStrict = request.optBoolean("cpuStrict", false) && cpuMask.isNotEmpty(),
                poll = request.optInt("poll", -1).coerceIn(-1, 100),
                threadPriority = request.optString("threadPriority", "normal"),
                loadMode = request.optString("loadMode", "auto"),
                hexUseHmx = request.optBoolean("hexUseHmx", false),
                hexDisableNhvx = request.optBoolean("hexDisableNhvx", false),
                hexHostBuf = request.optBoolean("hexHostBuf", false),
                hexOpBatch = request.optInt("hexOpBatch", 0).coerceIn(0, 0xF),
                hexNDev = request.optInt("hexNDev", 0).coerceIn(0, 8),
            )
        }
    }
}
