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
) {
    val pin: String get() = recipeSha256(indexJson.toByteArray(StandardCharsets.UTF_8))
}

data class StoredRecipeSource(
    val url: String,
    val current: RecipeSnapshot,
    val previous: RecipeSnapshot? = null,
)

/** An uncommitted download, with the old pin it was reviewed against to reject stale approvals. */
data class RecipeSourceReview(
    val url: String,
    val snapshot: RecipeSnapshot,
    val expectedPin: String?,
)

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
        val snapshot = readSnapshot(indexBytes, external = true) { name ->
            require(System.nanoTime() < deadline) { "Recipe source download exceeded its 45-second budget" }
            download.read(URI(url).resolve(name).toASCIIString(), io.github.kurue.bram.core.domain.MAX_RECIPE_DOCUMENT_BYTES, deadline)
        }
        old?.let {
            require(snapshot.index.source.id == it.current.index.source.id) { "Source identity changed; add it at a separate URL and review it as a new source" }
            (listOf(it.current) + listOfNotNull(it.previous)).forEach { known ->
                require(known.index.revision != snapshot.index.revision || known.pin == snapshot.pin) {
                    "Source reused revision ${snapshot.index.revision} for different bytes; publish a new revision"
                }
            }
        }
        return RecipeSourceReview(url, snapshot, old?.current?.pin)
    }

    @Synchronized
    fun accept(review: RecipeSourceReview) {
        val url = normalizeUrl(review.url)
        val file = fileFor(url)
        val old = file.takeIf { it.exists() }?.let(::readStored)
        require(old?.current?.pin == review.expectedPin) { "Source changed since review; check it again" }
        require(old != null || sourceFiles().size < MAX_EXTERNAL_SOURCES) { "Source cache is full" }
        // Review values are in-memory data, but recheck bytes at the persistence boundary as well.
        val snapshot = readSnapshot(review.snapshot.indexJson.toByteArray(StandardCharsets.UTF_8), true) { name ->
            review.snapshot.documents.getValue(name).toByteArray(StandardCharsets.UTF_8)
        }
        if (snapshot.pin == old?.current?.pin) return
        writeStored(StoredRecipeSource(url, snapshot, old?.current))
    }

    @Synchronized
    fun rollback(url: String) {
        val old = readStored(fileFor(normalizeUrl(url)))
        val previous = requireNotNull(old.previous) { "No previous snapshot is cached" }
        writeStored(old.copy(current = previous, previous = old.current))
    }

    @Synchronized
    fun remove(url: String) {
        val file = fileFor(normalizeUrl(url))
        require(!file.exists() || file.delete()) { "Could not remove the cached source" }
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
        require(obj.getInt("version") == 1) { "Unsupported recipe cache version" }
        val url = normalizeUrl(obj.getString("url"))
        require(fileFor(url).name == file.name) { "Cache URL does not match its file" }
        fun snapshot(value: JSONObject): RecipeSnapshot {
            val docs = value.getJSONObject("documents")
            return readSnapshot(value.getString("index").toByteArray(StandardCharsets.UTF_8), true) {
                docs.getString(it).toByteArray(StandardCharsets.UTF_8)
            }.also { require(it.pin == value.getString("pin")) { "Cached index hash mismatch" } }
        }
        return StoredRecipeSource(url, snapshot(obj.getJSONObject("current")), obj.optJSONObject("previous")?.let(::snapshot))
    }

    private fun writeStored(source: StoredRecipeSource) {
        fun encode(snapshot: RecipeSnapshot): JSONObject = JSONObject()
            .put("index", snapshot.indexJson).put("documents", JSONObject(snapshot.documents)).put("pin", snapshot.pin)
        val bytes = JSONObject().put("version", 1).put("url", source.url)
            .put("current", encode(source.current)).apply {
                source.previous?.let { put("previous", encode(it)) }
            }.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_CACHE_FILE_BYTES) { "Recipe source cache file is too large" }
        require(directory.isDirectory || directory.mkdirs()) { "Could not create recipe source cache" }
        val file = fileFor(source.url)
        val temporary = File(directory, "${file.name}.tmp")
        try {
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
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
            readDocument: (String) -> ByteArray,
        ): RecipeSnapshot {
            require(indexBytes.size <= io.github.kurue.bram.core.domain.MAX_RECIPE_INDEX_BYTES) { "Index is too large" }
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
            return RecipeSnapshot(indexJson, documents, index, recipes)
        }
    }
}
