package io.github.kurue.bram.runtime.openai

import io.github.kurue.bram.core.domain.ProviderProfile
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Reads the conventional OpenAI-compatible model catalog without making a generation request. */
data class RemoteModelInfo(
    val id: String,
    val contextWindowTokens: Int? = null,
    val reasoningEfforts: List<String> = emptyList(),
)

class RemoteModelCatalog {

    /**
     * Short, bounded reads: a catalog fetch has no stream to hold open, so unlike the generation
     * path it keeps a real read timeout. The read timeout is per-read rather than per-body, so a
     * slow provider still fails instead of hanging.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun list(baseUrl: String, apiKey: String = ""): List<RemoteModelInfo> = withContext(Dispatchers.IO) {
        var lastFailure: Throwable? = null
        for (candidate in modelUrls(baseUrl)) {
            runCatching { fetch(candidate, apiKey) }
                .onSuccess { models ->
                    val profile = ProviderProfile.forBaseUrl(baseUrl)
                    return@withContext if (profile.needsCatalogEnrichment) enrichOpenCodeGo(models) else models
                }
                .onFailure { lastFailure = it }
        }
        throw lastFailure ?: IllegalArgumentException("No model catalog URL could be derived")
    }

    private suspend fun fetch(url: String, apiKey: String): List<RemoteModelInfo> {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bram-Android/0.1")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        client.newCall(request).await().use { response ->
            val status = response.code
            val body = response.body?.string().orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
                    .getOrNull().takeUnless { it.isNullOrBlank() }
                    ?: body.take(1_024).ifBlank { "HTTP $status" }
                throw IllegalStateException("Model list failed ($status): $message")
            }
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            return buildList {
                for (index in 0 until data.length()) {
                    when (val item = data.opt(index)) {
                        is String -> add(RemoteModelInfo(item))
                        is JSONObject -> item.optString("id").takeIf(String::isNotBlank)?.let { id ->
                            add(
                                RemoteModelInfo(
                                    id = id,
                                    contextWindowTokens = item.optionalInt("context_window")
                                        ?: item.optionalInt("max_model_len")
                                        ?: item.optJSONObject("limit")?.optionalInt("context"),
                                ),
                            )
                        }
                    }
                }
            }.distinctBy { it.id }.sortedBy { it.id }
        }
    }

    private suspend fun enrichOpenCodeGo(models: List<RemoteModelInfo>): List<RemoteModelInfo> = runCatching {
        val root = JSONObject(readUrl("https://models.dev/api.json"))
        val catalog = root.optJSONObject("opencode-go")?.optJSONObject("models") ?: return@runCatching models
        models.map { model ->
            val metadata = catalog.optJSONObject(model.id) ?: return@map model
            val efforts = buildList {
                val options = metadata.optJSONArray("reasoning_options") ?: JSONArray()
                for (index in 0 until options.length()) {
                    val values = options.optJSONObject(index)?.optJSONArray("values") ?: continue
                    for (valueIndex in 0 until values.length()) values.optString(valueIndex).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            model.copy(
                contextWindowTokens = metadata.optJSONObject("limit")?.optionalInt("context")
                    ?: model.contextWindowTokens,
                reasoningEfforts = efforts.distinct(),
            )
        }
    }.getOrDefault(models)

    private suspend fun readUrl(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Bram-Android/0.1")
            .build()
        return client.newCall(request).await().use { response ->
            if (response.code !in 200..299) error("Metadata lookup failed")
            response.body?.string().orEmpty()
        }
    }
}

private fun JSONObject.optionalInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name).takeIf { it > 0 } else null

/**
 * Accepts a server root, a /v1 root, or a pasted generation endpoint. Root URLs try the common
 * /v1/models location first, then /models for servers that expose the API without a version path.
 */
internal fun modelUrls(baseUrl: String): List<String> {
    val uri = URI(baseUrl.trim().trimEnd('/'))
    val path = uri.path.orEmpty().trimEnd('/')
    val apiRoot = when {
        path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions")
        path.endsWith("/responses") -> path.removeSuffix("/responses")
        else -> path
    }
    fun at(newPath: String) = URI(uri.scheme, uri.userInfo, uri.host, uri.port, newPath, null, null).toString()
    return if (apiRoot.isBlank() || apiRoot == "/") {
        listOf(at("/v1/models"), at("/models"))
    } else {
        listOf(at("$apiRoot/models"))
    }
}
