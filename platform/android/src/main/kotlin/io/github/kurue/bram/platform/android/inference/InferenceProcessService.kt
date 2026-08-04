package io.github.kurue.bram.platform.android.inference

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

/**
 * Native inference deliberately lives in a second process. The current implementation is a
 * protocol scaffold; it returns a structured unavailable event until llama.cpp is linked.
 */
class InferenceProcessService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val requests = ConcurrentHashMap<String, Future<*>>()

    private val binder = object : IInferenceService.Stub() {
        override fun probe(): String = JSONObject()
            .put("protocolVersion", 1)
            .put("process", ":inference")
            .put("nativeRuntimeLinked", false)
            .toString()

        override fun generate(requestId: String, requestJson: String, callback: IInferenceCallback) {
            requests[requestId]?.cancel(true)
            requests[requestId] = executor.submit {
                val event = JSONObject()
                    .put("type", "failed")
                    .put("recoverable", true)
                    .put("message", "The llama.cpp native runtime has not been linked yet")
                    .toString()
                runCatching { callback.onEvent(requestId, event) }
                requests.remove(requestId)
            }
        }

        override fun cancel(requestId: String) {
            requests.remove(requestId)?.cancel(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        requests.values.forEach { it.cancel(true) }
        requests.clear()
        executor.shutdownNow()
        super.onDestroy()
    }
}
