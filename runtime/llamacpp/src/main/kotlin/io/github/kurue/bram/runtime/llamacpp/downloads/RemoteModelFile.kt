package io.github.kurue.bram.runtime.llamacpp.downloads

/**
 * One GGUF in a Hugging Face repository, as the tree API describes it.
 *
 * [sha256] is the LFS object id, which is what makes a download safe to trust: the file is
 * verified against it before it is imported, the same way a SAF import is hashed while copying.
 */
data class RemoteModelFile(
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    /** What a person would call this model: the filename without its extension. */
    val displayName: String
        get() = fileName.removeSuffix(".gguf").removeSuffix(".GGUF").trim()
}

/** A Hugging Face request failed, carrying a message a person can act on. */
class DownloadSourceException(message: String) : IllegalStateException(message)
