package io.github.kurue.bram.runtime.llamacpp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelId
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ModelImportProgress(
    val stage: String,
    val bytesRead: Long = 0,
    val totalBytes: Long = 0,
)

class LocalModelStore(
    context: Context,
    private val metadataReader: GgufMetadataReader = GgufMetadataReader(),
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val modelsDirectory = java.io.File(appContext.filesDir, "models")

    suspend fun list(): List<LocalModelRecord> = withContext(Dispatchers.IO) {
        decode(preferences.getString(KEY_MODELS, null))
            .sortedByDescending(LocalModelRecord::importedAtEpochMillis)
    }

    suspend fun importModel(
        uri: Uri,
        progress: (ModelImportProgress) -> Unit = {},
    ): LocalModelRecord = withContext(Dispatchers.IO) {
        val document = queryDocument(uri)
        require(document.size > 0) { "The selected document is empty or its size is unavailable" }

        progress(ModelImportProgress("Reading GGUF metadata", totalBytes = document.size))
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("The selected document could not be opened")
        val metadata = try {
            requireSeekable(descriptor.fileDescriptor, uri)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(metadataReader::read)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }

        // Native llama.cpp re-opens the model by path, which scoped storage denies for
        // provider-granted descriptors. Copy the bytes into app-private storage, hashing in the
        // same pass, and load from the copy from then on.
        modelsDirectory.mkdirs()
        val stagingFile = java.io.File.createTempFile("import-", ".gguf.part", modelsDirectory)
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
                        progress(ModelImportProgress("Copying and verifying SHA-256", readTotal, document.size))
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalArgumentException("The selected document could not be read")
            require(readTotal == document.size) {
                "The model changed while it was being copied (${readTotal} of ${document.size} bytes read)"
            }
        } catch (error: Throwable) {
            runCatching { stagingFile.delete() }
            throw error
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val modelFile = java.io.File(modelsDirectory, "${hash.take(24)}.gguf")
        if (!stagingFile.renameTo(modelFile)) {
            runCatching { stagingFile.delete() }
            throw IllegalStateException("Could not move the copied GGUF into app storage")
        }
        val record = LocalModelRecord(
            id = ModelId("local:${hash.take(24)}"),
            displayName = metadata.name?.takeIf(String::isNotBlank)
                ?: document.fileName.removeSuffix(".gguf").removeSuffix(".GGUF"),
            fileName = document.fileName,
            contentUri = uri.toString(),
            localPath = modelFile.absolutePath,
            fileSizeBytes = document.size,
            sha256 = hash,
            ggufVersion = metadata.version,
            architecture = metadata.architecture,
            quantization = metadata.quantization,
            trainedContextTokens = metadata.trainedContextTokens,
            layerCount = metadata.layerCount,
            hasChatTemplate = metadata.hasChatTemplate,
            preferredContextTokens = recommendInitialContext(metadata.trainedContextTokens),
        )

        val models = decode(preferences.getString(KEY_MODELS, null)).toMutableList()
        val replaced = models.filter { it.id == record.id || it.contentUri == record.contentUri }
        models.removeAll { it.id == record.id || it.contentUri == record.contentUri }
        models += record
        persist(models)
        replaced.asSequence()
            .map(LocalModelRecord::localPath)
            .filter { path ->
                path.isNotBlank() && path != record.localPath && models.none { it.localPath == path }
            }
            .distinct()
            .forEach { path -> runCatching { java.io.File(path).delete() } }
        progress(ModelImportProgress("Verified", document.size, document.size))
        record
    }

    suspend fun updatePreferredContext(modelId: ModelId, tokens: Int) = withContext(Dispatchers.IO) {
        val models = decode(preferences.getString(KEY_MODELS, null)).map { model ->
            if (model.id != modelId) return@map model
            val upper = model.trainedContextTokens.takeIf { it > 0 } ?: 1_000_000
            model.copy(preferredContextTokens = tokens.coerceIn(256, upper))
        }
        persist(models)
    }

    suspend fun remove(modelId: ModelId) = withContext(Dispatchers.IO) {
        val models = decode(preferences.getString(KEY_MODELS, null)).toMutableList()
        val removed = models.firstOrNull { it.id == modelId } ?: return@withContext
        models.remove(removed)
        persist(models)
        if (removed.localPath.isNotBlank() && models.none { it.localPath == removed.localPath }) {
            runCatching { java.io.File(removed.localPath).delete() }
        }
        if (models.none { it.contentUri == removed.contentUri }) {
            releasePermission(removed.contentUri)
        }
    }

    private fun releasePermission(uriString: String) {
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
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
            fileName = fileName?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "model.gguf",
            size = size?.takeIf { it >= 0 } ?: fallbackSize ?: -1,
        )
    }

    private fun requireSeekable(fileDescriptor: java.io.FileDescriptor, uri: Uri) {
        val position = runCatching { Os.lseek(fileDescriptor, 0, OsConstants.SEEK_CUR) }
            .getOrElse {
                throw NonSeekableModelException(
                    "${resolver.getType(uri) ?: "This document provider"} does not expose a seekable model file. " +
                        "Choose the file from local device storage.",
                )
            }
        Os.lseek(fileDescriptor, position, OsConstants.SEEK_SET)
    }

    private fun persist(models: List<LocalModelRecord>) {
        val array = JSONArray()
        models.forEach { array.put(it.toJson()) }
        check(preferences.edit().putString(KEY_MODELS, array.toString()).commit()) {
            "Could not persist the local model catalog"
        }
    }

    private fun decode(raw: String?): List<LocalModelRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toRecord())
            }
        }.getOrDefault(emptyList())
    }

    private fun LocalModelRecord.toJson(): JSONObject = JSONObject()
        .put("id", id.value)
        .put("displayName", displayName)
        .put("fileName", fileName)
        .put("contentUri", contentUri)
        .put("localPath", localPath)
        .put("fileSizeBytes", fileSizeBytes)
        .put("sha256", sha256)
        .put("ggufVersion", ggufVersion)
        .put("architecture", architecture)
        .put("quantization", quantization)
        .put("trainedContextTokens", trainedContextTokens)
        .put("layerCount", layerCount)
        .put("hasChatTemplate", hasChatTemplate)
        .put("importedAtEpochMillis", importedAtEpochMillis)
        .put("preferredContextTokens", preferredContextTokens)

    private fun JSONObject.toRecord(): LocalModelRecord = LocalModelRecord(
        id = ModelId(getString("id")),
        displayName = getString("displayName"),
        fileName = getString("fileName"),
        contentUri = getString("contentUri"),
        localPath = optString("localPath"),
        fileSizeBytes = getLong("fileSizeBytes"),
        sha256 = getString("sha256"),
        ggufVersion = getInt("ggufVersion"),
        architecture = getString("architecture"),
        quantization = getString("quantization"),
        trainedContextTokens = getInt("trainedContextTokens"),
        layerCount = getInt("layerCount"),
        hasChatTemplate = getBoolean("hasChatTemplate"),
        importedAtEpochMillis = getLong("importedAtEpochMillis"),
        preferredContextTokens = getInt("preferredContextTokens"),
    )

    private fun recommendInitialContext(trainedMaximum: Int): Int {
        if (trainedMaximum <= 0) return 4_096
        // Keep the first physical-device probation conservative. Larger trained contexts remain
        // selectable after the 8K CPU plan has demonstrated stable memory use on the device.
        return listOf(8_192, 4_096, 2_048, 1_024, 512, 256)
            .firstOrNull { it <= trainedMaximum }
            ?: trainedMaximum
    }

    private data class DocumentInfo(val fileName: String, val size: Long)

    private companion object {
        const val PREFERENCES = "bram-local-models-v1"
        const val KEY_MODELS = "models"
        const val HASH_BUFFER_BYTES = 4 * 1024 * 1024
    }
}

class NonSeekableModelException(message: String) : IllegalArgumentException(message)
