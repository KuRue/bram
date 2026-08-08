package io.github.kurue.bram.runtime.llamacpp.downloads

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Lists the GGUFs a Hugging Face repository publishes.
 *
 * The tree API is used rather than the model metadata because the metadata endpoint lists a
 * handful of sibling files, not the full branch: quantizations live alongside the original in the
 * same repo, and a person should be able to pick the size they want. Only LFS files with an object
 * id are offered, because a file without a SHA-256 to verify against cannot be trusted the way an
 * import expects to be.
 */
class HuggingFaceCatalog(
    private val baseUrl: String = "https://huggingface.co",
    private val userAgent: String = "Bram-Android/0.1",
) {
    /**
     * Every GGUF in the repository's main branch, smallest first so the cheapest option leads.
     *
     * @throws DownloadSourceException when the repository is missing, gated, or the request fails.
     */
    suspend fun listGgufFiles(repoId: String): List<RemoteModelFile> = withContext(Dispatchers.IO) {
        val normalized = repoId.trim().trim('/').removePrefix("https://huggingface.co/")
        require(normalized.isNotBlank() && '/' in normalized) {
            "Enter a repository id like Qwen/Qwen2.5-0.5B-Instruct-GGUF"
        }
        val connection = (URL("$baseUrl/api/models/$normalized/tree/main?recursive=true")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw DownloadSourceException(failureMessage(status, body, connection.getHeaderField("x-error-message")))
            }
            parseHuggingFaceTree(body, normalized)
        } finally {
            connection.disconnect()
        }
    }

    /** The CDN-backed URL a browser download would get, which follows the redirects for us. */
    fun resolveDownloadUrl(repoId: String, fileName: String): String =
        "$baseUrl/$repoId/resolve/main/$fileName?download=true"

    private fun failureMessage(status: Int, body: String, errorHeader: String?): String {
        val serverMessage = errorHeader?.takeIf(String::isNotBlank)
            ?: body.trim().takeIf(String::isNotBlank)?.take(2_000)
        return when (status) {
            401, 403 -> serverMessage?.let { "This repository is gated or private: $it" }
                ?: "This repository is gated or private"
            404 -> serverMessage?.let { "No such repository: $it" } ?: "No such repository"
            else -> serverMessage?.let { "Hugging Face replied with HTTP $status: $it" }
                ?: "Hugging Face replied with HTTP $status"
        }
    }
}

/**
 * Pure parsing of the tree API response, separated so it can be unit-tested against fixtures.
 *
 * A file is offered only when it is a GGUF on a real branch path and carries an LFS object id;
 * folders, symlinks, and plain files are skipped. The parser never throws on a malformed entry —
 * one odd row should not hide the files that parsed.
 */
internal fun parseHuggingFaceTree(body: String, repoId: String): List<RemoteModelFile> {
    val array = runCatching { JSONArray(body) }.getOrElse { return emptyList() }
    val files = mutableListOf<RemoteModelFile>()
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        if (entry.optString("type") != "file") continue
        val path = entry.optString("path")
        if (!path.endsWith(".gguf", ignoreCase = true)) continue
        // Some publishers (e.g. the official Qwen repos) split a GGUF across LFS parts named
        // `...-00001-of-00003.gguf`. A single-part download is an incomplete file that will not
        // load, so multi-part files are hidden rather than offered; a repo whose only quants are
        // split will simply list nothing, which is honest about what a one-shot transfer can do.
        if (MULTI_PART_GGUF.containsMatchIn(path)) continue
        val lfs = entry.optJSONObject("lfs") ?: continue
        val sha = lfs.optString("oid")
        if (sha.isBlank()) continue
        files += RemoteModelFile(
            repoId = repoId,
            fileName = path,
            sizeBytes = lfs.optLong("size", -1L).coerceAtLeast(0L),
            sha256 = sha,
        )
    }
    return files.sortedBy(RemoteModelFile::sizeBytes)
}

private val MULTI_PART_GGUF = Regex("-\\d{5}-of-\\d{5}", RegexOption.IGNORE_CASE)
