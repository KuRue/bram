package io.github.kurue.bram.runtime.llamacpp.downloads

import io.github.kurue.bram.runtime.llamacpp.ModelImportProgress
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Downloads a GGUF into app-private storage, verifying its SHA-256 against the LFS object id.
 *
 * The file is staged under a `.gguf.part` name so an interrupted or cancelled download can never
 * be mistaken for a complete model, verified, and only then renamed to the digest name the store
 * expects. Cancellation deletes the staging file: a phone has no spare gigabytes for half a model.
 *
 * [progress] is invoked with the byte totals as they change, from the first chunk on, so the UI
 * can show a live fraction rather than an indeterminate bar.
 */
class ModelDownloader(
    private val catalog: HuggingFaceCatalog = HuggingFaceCatalog(),
    private val userAgent: String = "Bram-Android/0.1",
) {
    /** Downloads [file] into [stagingDirectory] and returns the verified, digest-named file. */
    suspend fun download(
        file: RemoteModelFile,
        stagingDirectory: File,
        progress: (ModelImportProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        stagingDirectory.mkdirs()
        val stagingFile = File.createTempFile("download-", ".gguf.part", stagingDirectory)
        var connection: HttpURLConnection? = null
        // A read blocked on a quiet socket will not notice cancellation on its own; severing
        // the connection makes it throw immediately, so Cancel always stops the download.
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) connection?.disconnect()
        }
        try {
            progress(ModelImportProgress("Downloading", 0, file.sizeBytes))
            connection = (URL(catalog.resolveDownloadUrl(file.repoId, file.fileName))
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                // A slow connection on a big file is normal; only an idle one is a failure.
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent)
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    .orEmpty()
                throw DownloadSourceException(
                    detail.trim().take(2_000).takeIf(String::isNotBlank)?.let {
                        "The download failed (HTTP $status): $it"
                    } ?: "The download failed (HTTP $status)",
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        // Cancellation lands here: the staging file is deleted on the way out.
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        total += count
                        progress(ModelImportProgress("Downloading", total, file.sizeBytes))
                    }
                    output.fd.sync()
                }
            }
            if (file.sizeBytes > 0 && total != file.sizeBytes) {
                throw DownloadSourceException(
                    "The download ended early ($total of ${file.sizeBytes} bytes received)",
                )
            }
            progress(ModelImportProgress("Verifying SHA-256", total, file.sizeBytes))
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == file.sha256) {
                "The downloaded file failed SHA-256 verification; the copy on the server " +
                    "may have changed. Try again."
            }
            val verified = File(stagingDirectory, "${file.sha256.take(24)}.gguf")
            if (!stagingFile.renameTo(verified)) {
                throw IllegalStateException("Could not move the verified download into storage")
            }
            progress(ModelImportProgress("Verified", total, file.sizeBytes))
            verified
        } catch (error: CancellationException) {
            runCatching { stagingFile.delete() }
            throw error
        } catch (error: Throwable) {
            runCatching { stagingFile.delete() }
            // A cancelled job surfaces its stop as a socket error, not a coroutine exception;
            // rewrap so callers can tell "stopped on purpose" from "failed".
            if (!coroutineContext.isActive) throw CancellationException("Download cancelled", error)
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 4 * 1024 * 1024
    }
}
