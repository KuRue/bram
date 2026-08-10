package io.github.kurue.bram.runtime.litertlm

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.kurue.bram.core.domain.LiteRtBackend
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.ModelId
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where a copy, import, or download stands. */
data class ImportProgress(
    val stage: String,
    val bytesRead: Long = 0,
    val totalBytes: Long = 0,
)

/**
 * The imported `.litertlm` packages, kept in app-private storage.
 *
 * A package is copied out of the picker into `filesDir/litert-models` under its digest name,
 * hashed in the same pass, exactly like the GGUF store — the engine re-opens the file by path,
 * which scoped storage refuses for provider-granted descriptors, so a private copy is the only
 * path that loads. There is no metadata header to read from a `.litertlm` file, so the record is
 * the picker's filename plus the verified hash.
 */
class LiteRtLmStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val packagesDirectory = java.io.File(appContext.filesDir, "litert-models")

    suspend fun list(): List<LiteRtModelRecord> = withContext(Dispatchers.IO) {
        decode(preferences.getString(KEY_PACKAGES, null))
            .sortedByDescending(LiteRtModelRecord::importedAtEpochMillis)
    }

    suspend fun lastLoadedPackageId(): String? = withContext(Dispatchers.IO) {
        preferences.getString(KEY_LAST_LOADED, null)
    }

    suspend fun setLastLoadedPackageId(modelId: String?) = withContext(Dispatchers.IO) {
        preferences.edit().apply {
            if (modelId == null) remove(KEY_LAST_LOADED) else putString(KEY_LAST_LOADED, modelId)
        }.apply()
    }

    /** Total bytes the imported packages occupy in app-private storage. */
    suspend fun storageBytesUsed(): Long = withContext(Dispatchers.IO) {
        packagesDirectory.listFiles()?.sumOf(java.io.File::length) ?: 0L
    }

    /** Deletes package copies no catalog record points at, and reports the bytes reclaimed. */
    suspend fun deleteOrphanedCopies(): Long = withContext(Dispatchers.IO) {
        val referenced = decode(preferences.getString(KEY_PACKAGES, null))
            .map(LiteRtModelRecord::localPath)
            .filter(String::isNotBlank)
            .toSet()
        packagesDirectory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.absolutePath !in referenced }
            .sumOf { file ->
                val size = file.length()
                if (file.delete()) size else 0L
            }
    }

    /** Copies the picked package into app storage, hashing and verifying as it goes. */
    suspend fun importPackage(
        uri: Uri,
        progress: (ImportProgress) -> Unit = {},
    ): LiteRtModelRecord = withContext(Dispatchers.IO) {
        val document = queryDocument(uri)
        require(document.size > 0) { "The selected document is empty or its size is unavailable" }
        require(document.fileName.endsWith(".litertlm", ignoreCase = true)) {
            "Choose a .litertlm package; a GGUF goes through Import model instead."
        }

        packagesDirectory.mkdirs()
        val stagingFile = java.io.File.createTempFile("import-", ".litertlm.part", packagesDirectory)
        val digest = MessageDigest.getInstance("SHA-256")
        var readTotal = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(HASH_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        readTotal += count
                        progress(ImportProgress("Copying and verifying SHA-256", readTotal, document.size))
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalArgumentException("The selected document could not be read")
            require(readTotal == document.size) {
                "The package changed while it was being copied (${readTotal} of ${document.size} bytes read)"
            }
        } catch (error: Throwable) {
            runCatching { stagingFile.delete() }
            throw error
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val packageFile = java.io.File(packagesDirectory, "${hash.take(24)}.litertlm")
        if (!stagingFile.renameTo(packageFile)) {
            runCatching { stagingFile.delete() }
            throw IllegalStateException("Could not move the copied package into app storage")
        }
        val record = LiteRtModelRecord(
            id = ModelId("litert:${hash.take(24)}"),
            displayName = displayNameFor(document.fileName),
            fileName = document.fileName,
            contentUri = uri.toString(),
            localPath = packageFile.absolutePath,
            fileSizeBytes = document.size,
            sha256 = hash,
        )
        addOrReplace(record, uri.toString())
        progress(ImportProgress("Verified", document.size, document.size))
        record
    }

    /** Which processor a package loads onto, remembered per package. */
    suspend fun setBackend(modelId: ModelId, backend: LiteRtBackend) = withContext(Dispatchers.IO) {
        val packages = decode(preferences.getString(KEY_PACKAGES, null)).toMutableList()
        val index = packages.indexOfFirst { it.id == modelId }
        if (index < 0) return@withContext
        packages[index] = packages[index].copy(backend = backend)
        persist(packages)
    }

    suspend fun remove(modelId: ModelId) = withContext(Dispatchers.IO) {
        val packages = decode(preferences.getString(KEY_PACKAGES, null)).toMutableList()
        val removed = packages.firstOrNull { it.id == modelId } ?: return@withContext
        packages.remove(removed)
        persist(packages)
        if (removed.localPath.isNotBlank() && packages.none { it.localPath == removed.localPath }) {
            runCatching { java.io.File(removed.localPath).delete() }
        }
        if (packages.none { it.contentUri == removed.contentUri }) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(removed.contentUri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun addOrReplace(record: LiteRtModelRecord, sourceKey: String) {
        val packages = decode(preferences.getString(KEY_PACKAGES, null)).toMutableList()
        val replaced = packages.filter { it.id == record.id || it.contentUri == sourceKey }
        packages.removeAll { it.id == record.id || it.contentUri == sourceKey }
        packages += record
        persist(packages)
        replaced.asSequence()
            .map(LiteRtModelRecord::localPath)
            .filter { path ->
                path.isNotBlank() && path != record.localPath && packages.none { it.localPath == path }
            }
            .distinct()
            .forEach { path -> runCatching { java.io.File(path).delete() } }
    }

    private fun queryDocument(uri: Uri): DocumentInfo {
        var fileName: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val fallbackSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { value -> value >= 0 } }
        return DocumentInfo(
            fileName = fileName?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "package.litertlm",
            size = size?.takeIf { it >= 0 } ?: fallbackSize ?: -1,
        )
    }

    private fun persist(packages: List<LiteRtModelRecord>) {
        val array = JSONArray()
        packages.forEach { array.put(it.toJson()) }
        check(preferences.edit().putString(KEY_PACKAGES, array.toString()).commit()) {
            "Could not persist the LiteRT package catalog"
        }
    }

    private fun decode(raw: String?): List<LiteRtModelRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toRecord())
            }
        }.getOrDefault(emptyList())
    }

    private fun LiteRtModelRecord.toJson(): JSONObject = JSONObject()
        .put("id", id.value)
        .put("displayName", displayName)
        .put("fileName", fileName)
        .put("contentUri", contentUri)
        .put("localPath", localPath)
        .put("fileSizeBytes", fileSizeBytes)
        .put("sha256", sha256)
        .put("backend", backend.wire)
        .put("importedAtEpochMillis", importedAtEpochMillis)

    private fun JSONObject.toRecord(): LiteRtModelRecord = LiteRtModelRecord(
        id = ModelId(getString("id")),
        displayName = displayNameFor(getString("fileName")),
        fileName = getString("fileName"),
        contentUri = getString("contentUri"),
        localPath = optString("localPath"),
        fileSizeBytes = getLong("fileSizeBytes"),
        sha256 = getString("sha256"),
        backend = LiteRtBackend.fromWire(optString("backend")),
        importedAtEpochMillis = getLong("importedAtEpochMillis"),
    )

    /** Long, unbroken, and entirely hexadecimal: a digest rather than a name. */
    private fun looksLikeHash(value: String): Boolean =
        value.length >= 16 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private fun displayNameFor(fileName: String): String {
        val fromFile = fileName.removeSuffix(".litertlm").removeSuffix(".LITERTLM").trim()
        return fromFile.takeIf(String::isNotBlank)?.takeUnless(::looksLikeHash) ?: "LiteRT-LM package"
    }

    private data class DocumentInfo(val fileName: String, val size: Long)

    private companion object {
        const val PREFERENCES = "bram-litert-models-v1"
        const val KEY_PACKAGES = "packages"
        const val KEY_LAST_LOADED = "lastLoadedPackageId"
        const val HASH_BUFFER_BYTES = 4 * 1024 * 1024
    }
}
