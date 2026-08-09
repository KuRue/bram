package io.github.kurue.bram.platform.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.kurue.bram.core.domain.McpServer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where configured MCP servers live, with the token in the Android Keystore exactly like
 * [SecureEndpointStore] keeps API keys: the metadata is plain preferences, the secret is
 * AES-GCM-encrypted with a key that never leaves the hardware, and a blank token on save removes
 * the stored one.
 */
class McpServerStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun list(): List<McpServer> = withContext(Dispatchers.IO) {
        val raw = preferences.getString(SERVERS_KEY, "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toServer())
            }
        }.getOrDefault(emptyList())
    }

    suspend fun upsert(server: McpServer, token: String?) = withContext(Dispatchers.IO) {
        val servers = list().associateByTo(linkedMapOf()) { it.id }
        servers[server.id] = server
        persistMetadata(servers.values.toList())

        when {
            token == null -> Unit
            token.isBlank() -> preferences.edit().remove(secretKey(server.id)).apply()
            else -> preferences.edit().putString(secretKey(server.id), encrypt(token)).apply()
        }
    }

    suspend fun remove(serverId: String) = withContext(Dispatchers.IO) {
        persistMetadata(list().filterNot { it.id == serverId })
        preferences.edit().remove(secretKey(serverId)).apply()
    }

    suspend fun resolveToken(serverId: String): String? = withContext(Dispatchers.IO) {
        preferences.getString(secretKey(serverId), null)?.let { encrypted ->
            runCatching { decrypt(encrypted) }.getOrNull()
        }
    }

    private fun persistMetadata(servers: List<McpServer>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        preferences.edit().putString(SERVERS_KEY, array.toString()).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val payload = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
        return payload.toString()
    }

    private fun decrypt(payload: String): String {
        val json = JSONObject(payload)
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun McpServer.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("baseUrl", baseUrl)
        .put("credentialAlias", credentialAlias)
        .put("allowInsecureHttp", allowInsecureHttp)

    private fun JSONObject.toServer(): McpServer = McpServer(
        id = getString("id"),
        displayName = getString("displayName"),
        baseUrl = getString("baseUrl"),
        credentialAlias = optString("credentialAlias", "mcp-token"),
        allowInsecureHttp = optBoolean("allowInsecureHttp", false),
    )

    private fun secretKey(serverId: String) = "secret.$serverId"

    private companion object {
        const val PREFERENCES_NAME = "bram.mcp_servers"
        const val SERVERS_KEY = "servers.v1"
        const val KEYSTORE_ALIAS = "bram.mcp_key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
