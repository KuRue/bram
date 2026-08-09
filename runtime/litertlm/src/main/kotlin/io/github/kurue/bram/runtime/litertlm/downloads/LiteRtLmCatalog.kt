package io.github.kurue.bram.runtime.litertlm.downloads

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * One `.litertlm` package in a Hugging Face repository, as the tree API describes it.
 *
 * The LiteRT community publishes its packages under `litert-community` repositories. Only LFS
 * files with an object id are offered, for the same reason the GGUF catalog demands one: a file
 * without a SHA-256 to verify against cannot be trusted the way an import expects to be.
 */
data class LiteRtModelFile(
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    /** What a person would call this model: the filename without its extension. */
    val displayName: String
        get() = fileName.removeSuffix(".litertlm").removeSuffix(".LITERTLM").trim()
}

/** A Hugging Face request failed, carrying a message a person can act on. */
class DownloadSourceException(message: String) : IllegalStateException(message)

/**
 * Lists the `.litertlm` packages a Hugging Face repository publishes.
 *
 * Same tree API and the same failure handling as the GGUF catalog; only the file filter differs.
 * A `.litertlm` file is one self-contained package, so multi-part splitting does not exist the way
 * it does for GGUFs.
 */
class LiteRtLmCatalog(
    private val baseUrl: String = "https://huggingface.co",
    private val userAgent: String = "Bram-Android/0.1",
) {
    /**
     * Every LiteRT-LM package in the repository's main branch, smallest first.
     *
     * @throws DownloadSourceException when the repository is missing, gated, or the request fails.
     */
    suspend fun listPackages(repoId: String): List<LiteRtModelFile> = withContext(Dispatchers.IO) {
        val normalized = repoId.trim().trim('/').removePrefix("https://huggingface.co/")
        require(normalized.isNotBlank() && '/' in normalized) {
            "Enter a repository id like litert-community/Gemma3-1B-IT"
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
            parseLiteRtLmTree(body, normalized)
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
 * A file is offered only when it is a `.litertlm` package on a real branch path and carries an LFS
 * object id; folders, symlinks, and plain files are skipped. The parser never throws on a malformed
 * entry — one odd row should not hide the files that parsed.
 */
internal fun parseLiteRtLmTree(body: String, repoId: String): List<LiteRtModelFile> {
    val array = runCatching { JSONArray(body) }.getOrElse { return emptyList() }
    val files = mutableListOf<LiteRtModelFile>()
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        if (entry.optString("type") != "file") continue
        val path = entry.optString("path")
        if (!path.endsWith(".litertlm", ignoreCase = true)) continue
        val lfs = entry.optJSONObject("lfs") ?: continue
        val sha = lfs.optString("oid")
        if (sha.isBlank()) continue
        files += LiteRtModelFile(
            repoId = repoId,
            fileName = path,
            sizeBytes = lfs.optLong("size", -1L).coerceAtLeast(0L),
            sha256 = sha,
        )
    }
    return files.sortedBy(LiteRtModelFile::sizeBytes)
}
