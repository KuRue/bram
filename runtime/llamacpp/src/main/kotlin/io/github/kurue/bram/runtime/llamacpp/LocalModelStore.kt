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

    /**
     * The model Bram last had loaded, so a relaunch can restore it without being asked.
     */
    suspend fun lastLoadedModelId(): String? = withContext(Dispatchers.IO) {
        preferences.getString(KEY_LAST_LOADED, null)
    }

    suspend fun setLastLoadedModelId(modelId: String?) = withContext(Dispatchers.IO) {
        preferences.edit().apply {
            if (modelId == null) remove(KEY_LAST_LOADED) else putString(KEY_LAST_LOADED, modelId)
        }.apply()
    }

    /** Total bytes the imported copies occupy in app-private storage. */
    suspend fun storageBytesUsed(): Long = withContext(Dispatchers.IO) {
        modelsDirectory.listFiles()?.sumOf(java.io.File::length) ?: 0L
    }

    /**
     * Deletes model copies no catalog record points at, and reports the bytes reclaimed.
     *
     * Each copy is roughly the size of the original GGUF, so an interrupted import or a cleared
     * catalog can strand well over a gigabyte with nothing in the UI referencing it. Partial
     * `.gguf.part` files from a failed copy are removed too.
     */
    suspend fun deleteOrphanedCopies(): Long = withContext(Dispatchers.IO) {
        val referenced = decode(preferences.getString(KEY_MODELS, null))
            .flatMap(::filePathsOf)
            .filter(String::isNotBlank)
            .toSet()
        modelsDirectory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.absolutePath !in referenced }
            .sumOf { file ->
                val size = file.length()
                if (file.delete()) size else 0L
            }
    }

    suspend fun importModel(
        uri: Uri,
        progress: (ModelImportProgress) -> Unit = {},
    ): LocalModelRecord = importModel(listOf(uri), progress)

    /**
     * Imports one GGUF, or the parts of a multi-part GGUF when more than one document is picked.
     *
     * Multi-part files are stored under their original `<name>-NNNNN-of-MMMMM.gguf` names —
     * llama.cpp's loader derives the sibling list from that pattern and the split metadata in
     * the first part — so the record keeps the first part's path and the rest beside it.
     */
    suspend fun importModel(
        uris: List<Uri>,
        progress: (ModelImportProgress) -> Unit = {},
    ): LocalModelRecord = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "No documents were selected" }
        if (uris.size == 1) return@withContext importSingle(uris.first(), progress)

        val documents = uris.map(::queryDocument)
        val sorted = documents.sortedBy { it.fileName }
        val splitSpec = splitSpecOf(sorted)
            ?: throw IllegalArgumentException(
                "The selected files do not form a multi-part GGUF (expected names like " +
                    "model-00001-of-00005.gguf). Import each part set together, or import a " +
                    "single-file GGUF.",
            )
        require(sorted.size == splitSpec.total) {
            "Selected ${sorted.size} of ${splitSpec.total} parts of ${splitSpec.prefix}; pick every part"
        }
        val totalBytes = sorted.sumOf { it.size }
        progress(ModelImportProgress("Reading GGUF metadata", totalBytes = totalBytes))

        // The split header lives in the first part; the metadata reader only needs its head.
        val metadata = readMetadata(uris.first())
        modelsDirectory.mkdirs()

        val staged = mutableListOf<Pair<java.io.File, String>>()
        val digests = mutableListOf<String>()
        var readTotal = 0L
        try {
            sorted.forEachIndexed { index, document ->
                val partUri = uris[index]
                val stagingFile = java.io.File.createTempFile("import-", ".gguf.part", modelsDirectory)
                staged += stagingFile to document.fileName
                val digest = MessageDigest.getInstance("SHA-256")
                var partBytes = 0L
                resolver.openInputStream(partUri)?.use { input ->
                    java.io.FileOutputStream(stagingFile).use { output ->
                        val buffer = ByteArray(HASH_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            partBytes += count
                            readTotal += count
                            progress(ModelImportProgress("Copying part ${index + 1} of ${sorted.size}", readTotal, totalBytes))
                        }
                        output.fd.sync()
                    }
                } ?: throw IllegalArgumentException("Part ${index + 1} could not be read")
                require(partBytes == document.size) {
                    "Part ${index + 1} changed while it was being copied " +
                        "($partBytes of ${document.size} bytes read)"
                }
                digests += digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (error: Throwable) {
            staged.forEach { (file, _) -> runCatching { file.delete() } }
            throw error
        }

        // Move each staging file to its ORIGINAL name, which the loader's sibling detection reads.
        val finalFiles = staged.map { (staging, name) ->
            val target = java.io.File(modelsDirectory, name)
            if (target.exists()) runCatching { target.delete() }
            if (!staging.renameTo(target)) {
                runCatching { staging.delete() }
                throw IllegalStateException("Could not move $name into app storage")
            }
            target
        }
        val firstHash = digests.first()
        val record = LocalModelRecord(
            id = ModelId("local:${firstHash.take(24)}"),
            displayName = displayNameFor(metadata.name, splitSpec.prefix + ".gguf"),
            fileName = splitSpec.prefix + ".gguf",
            contentUri = uris.first().toString(),
            localPath = finalFiles.first().absolutePath,
            fileSizeBytes = totalBytes,
            sha256 = firstHash,
            parts = finalFiles.drop(1).map(java.io.File::getAbsolutePath),
            ggufVersion = metadata.version,
            architecture = metadata.architecture,
            quantization = metadata.quantization,
            trainedContextTokens = metadata.trainedContextTokens,
            layerCount = metadata.layerCount,
            hasChatTemplate = metadata.hasChatTemplate,
            preferredContextTokens = recommendInitialContext(metadata.trainedContextTokens),
            tensorTypeCounts = metadata.tensorTypeCounts,
        )
        addOrReplace(record, uris.first().toString())
        progress(ModelImportProgress("Verified", totalBytes, totalBytes))
        record
    }

    /** Reads the GGUF header metadata from a provider document, as the single-file import does. */
    private fun readMetadata(uri: Uri): GgufMetadata {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("The selected document could not be opened")
        return try {
            requireSeekable(descriptor.fileDescriptor, uri)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(metadataReader::read)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    private data class SplitSpec(val prefix: String, val total: Int)

    /**
     * Validates a set of part names against llama.cpp's split pattern
     * `<name>-NNNNN-of-MMMMM.gguf` and returns the shared prefix and part count, or null when the
     * set does not form a complete multi-part file.
     */
    private fun splitSpecOf(documents: List<DocumentInfo>): SplitSpec? {
        val pattern = Regex(""".*-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)
        val matches = documents.map { document ->
            val match = pattern.find(document.fileName) ?: return null
            Triple(match.groupValues[1].toIntOrNull() ?: return null,
                match.groupValues[2].toIntOrNull() ?: return null,
                document.fileName.substring(0, match.range.first))
        }
        val total = matches.first().second
        if (matches.any { it.second != total }) return null
        val prefix = matches.first().third
        if (matches.any { it.third != prefix }) return null
        val indices = matches.map { it.first }.sorted()
        if (indices != (1..total).toList()) return null
        return SplitSpec(prefix, total)
    }

    /** The single-file import path, kept exactly as before; multi-part imports call this too. */
    private suspend fun importSingle(
        uri: Uri,
        progress: (ModelImportProgress) -> Unit = {},
    ): LocalModelRecord = withContext(Dispatchers.IO) {
        val document = queryDocument(uri)
        require(document.size > 0) { "The selected document is empty or its size is unavailable" }

        progress(ModelImportProgress("Reading GGUF metadata", totalBytes = document.size))
        val metadata = readMetadata(uri)

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
            displayName = displayNameFor(metadata.name, document.fileName),
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
            tensorTypeCounts = metadata.tensorTypeCounts,
        )
        addOrReplace(record, uri.toString())
        progress(ModelImportProgress("Verified", document.size, document.size))
        record
    }

    /**
     * Registers a GGUF that is already in app-private storage (a quant conversion's output):
     * hashes it, reads its metadata, and adds the catalog record. There is no provider URI to
     * keep, so the content source is blank and the file path is the replace key.
     */
    suspend fun registerLocalFile(
        file: java.io.File,
        displayNameSuffix: String,
    ): LocalModelRecord = withContext(Dispatchers.IO) {
        require(file.isFile) { "The converted GGUF is missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val metadata = java.io.FileInputStream(file).use(metadataReader::read)
        val record = LocalModelRecord(
            id = ModelId("local:${hash.take(24)}"),
            // The quant suffix disambiguates: the metadata name is identical for a converted
            // copy, and two records named the same thing are indistinguishable on the card.
            displayName = displayNameFor(metadata.name, file.name) +
                if (displayNameSuffix.isBlank()) "" else " · $displayNameSuffix",
            fileName = file.name,
            contentUri = "",
            localPath = file.absolutePath,
            fileSizeBytes = file.length(),
            sha256 = hash,
            ggufVersion = metadata.version,
            architecture = metadata.architecture,
            quantization = metadata.quantization,
            trainedContextTokens = metadata.trainedContextTokens,
            layerCount = metadata.layerCount,
            hasChatTemplate = metadata.hasChatTemplate,
            preferredContextTokens = recommendInitialContext(metadata.trainedContextTokens),
            tensorTypeCounts = metadata.tensorTypeCounts,
        )
        addOrReplace(record, file.absolutePath)
        record
    }

    /**
     * Records a model in the catalog, replacing any record with the same id or source, and deletes
     * the copies the replaced records left behind.
     */
    private fun addOrReplace(record: LocalModelRecord, sourceKey: String) {
        val models = decode(preferences.getString(KEY_MODELS, null)).toMutableList()
        val replaced = models.filter { it.id == record.id || it.contentUri == sourceKey }
        models.removeAll { it.id == record.id || it.contentUri == sourceKey }
        models += record
        persist(models)
        val stillReferenced = models.flatMap(::filePathsOf).toSet()
        replaced.asSequence()
            .flatMap(::filePathsOf)
            .filter { path -> path.isNotBlank() && path !in stillReferenced }
            .distinct()
            .forEach { path -> runCatching { java.io.File(path).delete() } }
    }

    // The per-model backend, context, and reasoning settings are no longer written here: they are
    // profile settings now. The fields remain on the record because a catalog written before
    // profiles existed is migrated from them the first time its default profile is created.
    suspend fun remove(modelId: ModelId) = withContext(Dispatchers.IO) {
        val models = decode(preferences.getString(KEY_MODELS, null)).toMutableList()
        val removed = models.firstOrNull { it.id == modelId } ?: return@withContext
        models.remove(removed)
        persist(models)
        val stillReferenced = models.flatMap(::filePathsOf).toSet()
        filePathsOf(removed)
            .filter { path -> path.isNotBlank() && path !in stillReferenced }
            .forEach { path -> runCatching { java.io.File(path).delete() } }
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
        .put("parts", JSONArray().also { array -> parts.forEach(array::put) })
        .put("ggufVersion", ggufVersion)
        .put("architecture", architecture)
        .put("quantization", quantization)
        .put("trainedContextTokens", trainedContextTokens)
        .put("layerCount", layerCount)
        .put("hasChatTemplate", hasChatTemplate)
        .put("importedAtEpochMillis", importedAtEpochMillis)
        .put("preferredContextTokens", preferredContextTokens)
        .put("preferredBackendId", preferredBackendId)
        .put("thinkingEnabled", thinkingEnabled)
        .put(
            "tensorTypeCounts",
            JSONObject().also { counts ->
                tensorTypeCounts.forEach { (type, count) -> counts.put(type, count) }
            },
        )

    private fun JSONObject.toRecord(): LocalModelRecord = LocalModelRecord(
        id = ModelId(getString("id")),
        displayName = displayNameFor(getString("displayName"), getString("fileName")),
        fileName = getString("fileName"),
        contentUri = getString("contentUri"),
        localPath = optString("localPath"),
        fileSizeBytes = getLong("fileSizeBytes"),
        sha256 = getString("sha256"),
        parts = optJSONArray("parts")?.let { array ->
            buildList(array.length()) { for (index in 0 until array.length()) add(array.getString(index)) }
        }.orEmpty(),
        ggufVersion = getInt("ggufVersion"),
        architecture = getString("architecture"),
        quantization = getString("quantization"),
        trainedContextTokens = getInt("trainedContextTokens"),
        layerCount = getInt("layerCount"),
        hasChatTemplate = getBoolean("hasChatTemplate"),
        importedAtEpochMillis = getLong("importedAtEpochMillis"),
        preferredContextTokens = getInt("preferredContextTokens"),
        preferredBackendId = optString("preferredBackendId"),
        thinkingEnabled = optBoolean("thinkingEnabled", false),
        tensorTypeCounts = optJSONObject("tensorTypeCounts")?.let { counts ->
            buildMap {
                counts.keys().forEach { key -> put(key, counts.getInt(key)) }
            }
        }.orEmpty(),
    )

    /**
     * Picks a name a person would recognise.
     *
     * A GGUF's `general.name` is often useful, but plenty of published models set it to a commit
     * hash or a bare repository id, which tells the reader nothing. The filename they picked is
     * usually the informative one in that case, so anything that looks like a hash loses to it.
     */
    private fun displayNameFor(metadataName: String?, fileName: String): String {
        val fromFile = fileName.removeSuffix(".gguf").removeSuffix(".GGUF").trim()
        val candidate = metadataName?.trim().orEmpty()
        return when {
            candidate.isBlank() || looksLikeHash(candidate) -> fromFile.ifBlank { candidate }
            else -> candidate
        }.ifBlank { "Local model" }
    }

    /** Every file a model record owns: the main copy and any multi-part siblings. */
    private fun filePathsOf(record: LocalModelRecord): List<String> = buildList {
        if (record.localPath.isNotBlank()) add(record.localPath)
        addAll(record.parts)
    }

    /** Long, unbroken, and entirely hexadecimal: a digest rather than a name. */
    private fun looksLikeHash(value: String): Boolean =
        value.length >= 16 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

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
        const val KEY_LAST_LOADED = "lastLoadedModelId"
        const val HASH_BUFFER_BYTES = 4 * 1024 * 1024
    }
}

class NonSeekableModelException(message: String) : IllegalArgumentException(message)
