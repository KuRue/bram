package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.HardwareRecipe
import io.github.kurue.bram.core.domain.RecipeIndex
import io.github.kurue.bram.core.domain.RecipeParse
import io.github.kurue.bram.core.domain.RecipeSourceKind
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

private const val MAX_EXTERNAL_SOURCES = 8
private const val MAX_SNAPSHOT_BYTES = 1024 * 1024
private const val MAX_CACHE_FILE_BYTES = 5 * 1024 * 1024
private const val MAX_SOURCE_RECIPES = 16

internal fun recipeSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Trust is in the reviewed bytes, never in a source's self-declared 'official' flag. */
data class RecipeSnapshot(
    val indexJson: String,
    val documents: Map<String, String>,
    val index: RecipeIndex,
    val recipes: List<HardwareRecipe>,
    val signature: RecipeSignature? = null,
) {
    val pin: String get() = recipeSha256(indexJson.toByteArray(StandardCharsets.UTF_8))
}

data class StoredRecipeSource(
    val url: String,
    val current: RecipeSnapshot,
    val previous: RecipeSnapshot? = null,
    val pinnedKey: String? = null,
    val stateId: String = "",
) {
    val canRollback: Boolean get() = previous != null && previous.signature?.publicKey == pinnedKey
}

/** An uncommitted download, with the old pin it was reviewed against to reject stale approvals. */
data class RecipeSourceReview(
    val url: String,
    val snapshot: RecipeSnapshot,
    val expectedPin: String?,
    val expectedKey: String? = null,
    val expectedState: String? = null,
) {
    val needsKeyApproval: Boolean get() = snapshot.signature?.publicKey != expectedKey
    val changesKey: Boolean get() = expectedKey != null && needsKeyApproval
}

data class RecipeSourcesUi(
    val bundled: RecipeSnapshot? = null,
    val sources: List<StoredRecipeSource> = emptyList(),
    val review: RecipeSourceReview? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

fun interface RecipeDownload {
    fun read(url: String, maxBytes: Int, deadlineNanos: Long): ByteArray
}

/** No supplied credentials, redirects, cleartext fallback, or unbounded bodies. */
class HttpsRecipeDownload(
    private val openConnection: (String) -> HttpsURLConnection = { URI(it).toURL().openConnection() as HttpsURLConnection },
) : RecipeDownload {
    override fun read(url: String, maxBytes: Int, deadlineNanos: Long): ByteArray {
        val connection = openConnection(RecipeSources.normalizeDocumentUrl(url))
        fun remainingMillis(): Int {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000
            require(remaining > 0) { "Recipe source download exceeded its 45-second budget" }
            return remaining.coerceAtMost(10_000).toInt()
        }
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = remainingMillis()
            connection.readTimeout = remainingMillis()
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode == 200) {
                "Recipe source returned HTTP ${connection.responseCode}; redirects are not followed"
            }
            require(connection.contentLengthLong <= maxBytes) { "Recipe source document exceeds $maxBytes bytes" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    connection.readTimeout = remainingMillis()
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    require(output.size() <= maxBytes) { "Recipe source document exceeds $maxBytes bytes" }
                }
                remainingMillis()
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Each source is one atomically replaced file containing two validated snapshots. Review is read
 * only; only accepting it changes the active pin. A failed download or write keeps the old file.
 * This store has no access to profiles or runtime settings.
 */
class RecipeSources(
    private val directory: File,
    private val bundledDocument: (String) -> ByteArray,
    private val download: RecipeDownload = HttpsRecipeDownload(),
) {
    fun bundled(): RecipeSnapshot = readSnapshot(
        bundledDocument("index.json"),
        external = false,
        readDocument = bundledDocument,
    )

    @Synchronized
    fun sources(): List<StoredRecipeSource> = sourceFiles().map(::readStored)

    // Read sources separately so one corrupt cache does not hide the healthy sources or the bundle.
    @Synchronized
    fun load(): RecipeSourcesUi {
        val errors = mutableListOf<String>()
        val bundle = runCatching { bundled() }.getOrElse {
            errors += "Bundled recipes: ${it.message}"
            null
        }
        val stored = sourceFiles().mapNotNull { file ->
            runCatching { readStored(file) }.getOrElse {
                errors += "Cached source ${file.name.take(12)}: ${it.message}"
                null
            }
        }
        return RecipeSourcesUi(bundled = bundle, sources = stored, error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    fun review(input: String): RecipeSourceReview {
        val url = normalizeUrl(input)
        val old = synchronized(this) { fileFor(url).takeIf { it.exists() }?.let(::readStored) }
        require(old != null || sourceFiles().size < MAX_EXTERNAL_SOURCES) { "At most $MAX_EXTERNAL_SOURCES external sources can be cached" }
        val deadline = System.nanoTime() + 45_000_000_000L
        val indexBytes = download.read(url, io.github.kurue.bram.core.domain.MAX_RECIPE_INDEX_BYTES, deadline)
        require(indexBytes.size <= io.github.kurue.bram.core.domain.MAX_RECIPE_INDEX_BYTES) { "Index is too large" }
        val signatureBytes = download.read(URI(url).resolve(RecipeSignature.FILE_NAME).toASCIIString(), RecipeSignature.MAX_BYTES, deadline)
        val snapshot = readSnapshot(indexBytes, external = true, signatureBytes = signatureBytes) { name ->
            require(System.nanoTime() < deadline) { "Recipe source download exceeded its 45-second budget" }
            download.read(URI(url).resolve(name).toASCIIString(), io.github.kurue.bram.core.domain.MAX_RECIPE_DOCUMENT_BYTES, deadline)
        }
        checkUpdateIdentity(old, snapshot)
        return RecipeSourceReview(url, snapshot, old?.current?.pin, old?.pinnedKey, old?.stateId)
    }

    private fun checkUpdateIdentity(old: StoredRecipeSource?, snapshot: RecipeSnapshot) {
        old?.let {
            require(snapshot.index.source.id == it.current.index.source.id) { "Source identity changed; add it at a separate URL and review it as a new source" }
            (listOf(it.current) + listOfNotNull(it.previous)).forEach { known ->
                require(known.index.revision != snapshot.index.revision || known.pin == snapshot.pin) {
                    "Source reused revision ${snapshot.index.revision} for different bytes; publish a new revision"
                }
            }
            // A validly signed but older index is a replay, not an update; Restore is the only way back.
            require(snapshot.pin == it.current.pin || !generatedBefore(snapshot.index.generatedAt, it.current.index.generatedAt)) {
                "Downloaded index (generated ${snapshot.index.generatedAt}) is older than the pinned snapshot " +
                    "(${it.current.index.generatedAt}); accepted snapshot kept"
            }
        }
    }

    // Both forms are validated UTC timestamps; a date-only value compares by day.
    private fun generatedBefore(candidate: String, pinned: String): Boolean =
        if (candidate.length == pinned.length) candidate < pinned else candidate.take(10) < pinned.take(10)

    @Synchronized
    fun accept(review: RecipeSourceReview, approvedKeyFingerprint: String? = null) {
        val url = normalizeUrl(review.url)
        val file = fileFor(url)
        val old = file.takeIf { it.exists() }?.let(::readStored)
        require(old?.current?.pin == review.expectedPin && old?.pinnedKey == review.expectedKey && old?.stateId == review.expectedState) {
            "Source changed since review; check it again"
        }
        require(old != null || sourceFiles().size < MAX_EXTERNAL_SOURCES) { "Source cache is full" }
        // Review values are in-memory data, but recheck bytes at the persistence boundary as well.
        val proof = requireNotNull(review.snapshot.signature) { "A signed index is required for network sources" }
        val snapshot = readSnapshot(review.snapshot.indexJson.toByteArray(StandardCharsets.UTF_8), true,
            signatureBytes = proof.json().toByteArray(StandardCharsets.UTF_8)) { name ->
            review.snapshot.documents.getValue(name).toByteArray(StandardCharsets.UTF_8)
        }
        checkUpdateIdentity(old, snapshot)
        if (proof.publicKey != old?.pinnedKey) {
            require(approvedKeyFingerprint == proof.fingerprint) { "Explicit approval of this signing key fingerprint is required" }
        }
        if (snapshot.pin == old?.current?.pin && proof.publicKey == old?.pinnedKey) return
        writeStored(StoredRecipeSource(url, snapshot, old?.current, proof.publicKey))
    }

    @Synchronized
    fun rollback(url: String) {
        val old = readStored(fileFor(normalizeUrl(url)))
        val previous = requireNotNull(old.previous) { "No previous snapshot is cached" }
        require(old.canRollback) { "The previous snapshot uses a different or unsigned key; restoring it requires a new key review" }
        writeStored(old.copy(current = previous, previous = old.current))
    }

    @Synchronized
    fun remove(url: String) {
        val file = fileFor(normalizeUrl(url))
        if (file.exists()) {
            require(file.delete()) { "Could not remove the cached source" }
            syncDirectory(directory)
        }
    }

    private fun sourceFiles(): List<File> = directory.listFiles { file ->
        file.name.matches(Regex("[0-9a-f]{64}\\.json"))
    }.orEmpty().sortedBy { it.name }.also {
        require(it.size <= MAX_EXTERNAL_SOURCES) { "Recipe source cache exceeds its source limit" }
    }

    private fun fileFor(url: String) = File(directory, "${recipeSha256(url.toByteArray(StandardCharsets.UTF_8))}.json")

    private fun readStored(file: File): StoredRecipeSource {
        require(file.length() in 1..MAX_CACHE_FILE_BYTES.toLong()) { "Cache file is empty or too large" }
        val obj = JSONObject(file.readText(StandardCharsets.UTF_8))
        val version = obj.getInt("version")
        require(version in 1..2) { "Unsupported recipe cache version" }
        val url = normalizeUrl(obj.getString("url"))
        require(fileFor(url).name == file.name) { "Cache URL does not match its file" }
        fun snapshot(value: JSONObject): RecipeSnapshot {
            val docs = value.getJSONObject("documents")
            val indexBytes = value.getString("index").toByteArray(StandardCharsets.UTF_8)
            require(recipeSha256(indexBytes) == value.getString("pin")) { "Cached index hash mismatch" }
            val signature = if (version == 2) value.optString("signature").takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8) else null
            return readSnapshot(indexBytes, true, signatureBytes = signature) {
                docs.getString(it).toByteArray(StandardCharsets.UTF_8)
            }.also { require(it.pin == value.getString("pin")) { "Cached index hash mismatch" } }
        }
        val current = snapshot(obj.getJSONObject("current"))
        val pinnedKey = if (version == 2) obj.optString("pinnedKey").takeIf { it.isNotEmpty() } else null
        require(current.signature?.publicKey == pinnedKey) { "Cached snapshot does not match its pinned signing key" }
        require(version != 2 || pinnedKey != null) { "Signed cache is missing its pinned signing key" }
        return StoredRecipeSource(url, current, obj.optJSONObject("previous")?.let(::snapshot), pinnedKey,
            if (version == 2) obj.getString("stateId").also { require(it.isNotBlank()) } else "legacy:${recipeSha256(file.readBytes())}")
    }

    private fun writeStored(source: StoredRecipeSource) {
        fun encode(snapshot: RecipeSnapshot): JSONObject = JSONObject()
            .put("index", snapshot.indexJson).put("documents", JSONObject(snapshot.documents)).put("pin", snapshot.pin)
            .apply { snapshot.signature?.let { put("signature", it.json()) } }
        val bytes = JSONObject().put("version", if (source.pinnedKey == null) 1 else 2).put("url", source.url)
            .put("pinnedKey", source.pinnedKey).put("stateId", UUID.randomUUID().toString())
            .put("current", encode(source.current)).apply {
                source.previous?.let { put("previous", encode(it)) }
            }.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_CACHE_FILE_BYTES) { "Recipe source cache file is too large" }
        if (!directory.isDirectory) {
            require(directory.mkdirs()) { "Could not create recipe source cache" }
            directory.parentFile?.let(::syncDirectory)
        }
        val file = fileFor(source.url)
        val temporary = File(directory, "${file.name}.tmp")
        try {
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally {
            temporary.delete()
        }
    }

    // File fsync protects the contents; directory fsync makes rename/removal durable across a
    // sudden shutdown. Without it an accepted/removal operation can disappear after a cold boot.
    private fun syncDirectory(file: File) {
        java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }

    companion object {
        /** GitHub shorthand requires an immutable commit. Generic indexes are pinned by bytes. */
        fun normalizeUrl(input: String): String {
            val shorthand = Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)@([0-9a-fA-F]{40})$").matchEntire(input.trim())
            val value = shorthand?.let {
                "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}/index.json"
            } ?: input.trim()
            val url = normalizeDocumentUrl(value)
            require(URI(url).rawPath.endsWith("/index.json")) {
                "Use an HTTPS URL ending in /index.json, or owner/repo@40-character-commit"
            }
            return url
        }

        /** Shared transport rules apply to both the index and its plain recipe filenames. */
        internal fun normalizeDocumentUrl(value: String): String {
            require(value.length <= 2048) { "Source URL is too long" }
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.port in listOf(-1, 443) &&
                uri.rawPath.endsWith(".json") && uri.normalize() == uri && !uri.rawPath.contains('%')) {
                "Use an HTTPS URL ending in /index.json without credentials, query, or fragment, or owner/repo@40-character-commit"
            }
            return uri.toASCIIString()
        }

        internal fun readSnapshot(
            indexBytes: ByteArray,
            external: Boolean,
            signatureBytes: ByteArray? = null,
            readDocument: (String) -> ByteArray,
        ): RecipeSnapshot {
            require(indexBytes.size <= io.github.kurue.bram.core.domain.MAX_RECIPE_INDEX_BYTES) { "Index is too large" }
            val signature = signatureBytes?.let { RecipeSignature.verify(indexBytes, it) }
            fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val indexJson = decode(indexBytes)
            val parsed = when (val result = RecipeIndex.parse(indexJson)) {
                is RecipeParse.Parsed -> result.value
                is RecipeParse.Refused -> error(result.problems.joinToString("\n"))
            }
            require(parsed.recipes.size <= MAX_SOURCE_RECIPES) { "A snapshot can contain at most $MAX_SOURCE_RECIPES recipes" }
            require(parsed.recipes.map { it.file }.distinct().size == parsed.recipes.size) { "Duplicate recipe files" }
            var size = indexBytes.size
            val documents = linkedMapOf<String, String>()
            val recipes = parsed.recipes.map { digest ->
                require(digest.file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.json")) && !digest.file.contains("..")) {
                    "Recipe file must be a plain JSON filename: ${digest.file}"
                }
                require(digest.file !in setOf("index.json", RecipeSignature.FILE_NAME)) { "Recipe file uses a reserved index filename" }
                val bytes = readDocument(digest.file)
                require(bytes.size <= io.github.kurue.bram.core.domain.MAX_RECIPE_DOCUMENT_BYTES) { "Recipe is too large: ${digest.file}" }
                size += bytes.size
                require(size <= MAX_SNAPSHOT_BYTES) { "Snapshot exceeds 1 MiB" }
                require(recipeSha256(bytes) == digest.sha256) { "Recipe hash mismatch: ${digest.file}" }
                val json = decode(bytes)
                val recipe = when (val result = HardwareRecipe.parse(json)) {
                    is RecipeParse.Parsed -> result.value
                    is RecipeParse.Refused -> error(result.problems.joinToString("\n"))
                }
                require(recipe.id == digest.id && recipe.version == digest.version) { "Recipe identity/version differs from index: ${digest.file}" }
                documents[digest.file] = json
                recipe
            }
            val index = if (external) parsed.copy(source = parsed.source.copy(kind = RecipeSourceKind.EXTERNAL)) else parsed
            return RecipeSnapshot(indexJson, documents, index, recipes, signature)
        }
    }
}
