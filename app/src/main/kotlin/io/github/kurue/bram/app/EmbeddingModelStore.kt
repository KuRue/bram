package io.github.kurue.bram.app

import android.content.Context

/**
 * Remembers which imported GGUF is loaded as the resident embedding model for memory recall.
 *
 * The model is loaded into the `:inference` process at startup and whenever the user changes it,
 * so the memory store can cosine-rank without a reload per query. Holds the id, the on-disk path
 * (what the runtime actually loads), and a display name for the UI.
 */
class EmbeddingModelStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelId(): String? = prefs.getString(KEY_ID, null)
    fun modelPath(): String? = prefs.getString(KEY_PATH, null)
    fun modelName(): String? = prefs.getString(KEY_NAME, null)

    fun set(id: String, path: String, name: String) {
        prefs.edit().putString(KEY_ID, id).putString(KEY_PATH, path).putString(KEY_NAME, name).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ID).remove(KEY_PATH).remove(KEY_NAME).apply()
    }

    private companion object {
        const val PREFS = "bram-embedding-model"
        const val KEY_ID = "id"
        const val KEY_PATH = "path"
        const val KEY_NAME = "name"
    }
}
