package io.github.kurue.bram.platform.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.RemoteEndpointStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SecureEndpointStore(
    context: Context,
) : RemoteEndpointStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun list(): List<RemoteEndpoint> = withContext(Dispatchers.IO) {
        val raw = preferences.getString(ENDPOINTS_KEY, "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toEndpoint())
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun upsert(endpoint: RemoteEndpoint, apiKey: String?) = withContext(Dispatchers.IO) {
        val endpoints = list().associateByTo(linkedMapOf()) { it.id }
        endpoints[endpoint.id] = endpoint
        persistMetadata(endpoints.values.toList())

        when {
            apiKey == null -> Unit
            apiKey.isBlank() -> preferences.edit().remove(secretKey(endpoint.id, endpoint.credentialAlias)).apply()
            else -> preferences.edit()
                .putString(secretKey(endpoint.id, endpoint.credentialAlias), encrypt(apiKey))
                .apply()
        }
    }

    override suspend fun remove(endpointId: String) = withContext(Dispatchers.IO) {
        val existing = list()
        val removed = existing.filter { it.id == endpointId }
        persistMetadata(existing.filterNot { it.id == endpointId })
        val edit = preferences.edit()
        removed.forEach { edit.remove(secretKey(endpointId, it.credentialAlias)) }
        edit.apply()
    }

    override suspend fun resolveCredential(endpointId: String, credentialAlias: String): String? =
        withContext(Dispatchers.IO) {
            preferences.getString(secretKey(endpointId, credentialAlias), null)?.let { encrypted ->
                runCatching { decrypt(encrypted) }.getOrNull()
            }
        }

    private fun persistMetadata(endpoints: List<RemoteEndpoint>) {
        val array = JSONArray()
        endpoints.forEach { array.put(it.toJson()) }
        preferences.edit().putString(ENDPOINTS_KEY, array.toString()).apply()
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

    private fun RemoteEndpoint.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("baseUrl", baseUrl)
        .put("modelName", modelName)
        .put("apiKind", apiKind.name)
        .put("contextWindowTokens", contextWindowTokens)
        .put("supportsToolCalling", supportsToolCalling)
        .put("allowInsecureHttp", allowInsecureHttp)
        .put("credentialAlias", credentialAlias)

    private fun JSONObject.toEndpoint(): RemoteEndpoint = RemoteEndpoint(
        id = getString("id"),
        displayName = getString("displayName"),
        baseUrl = getString("baseUrl"),
        modelName = getString("modelName"),
        apiKind = RemoteApiKind.valueOf(optString("apiKind", RemoteApiKind.CHAT_COMPLETIONS.name)),
        contextWindowTokens = optInt("contextWindowTokens", 32_768),
        supportsToolCalling = optBoolean("supportsToolCalling", true),
        allowInsecureHttp = optBoolean("allowInsecureHttp", false),
        credentialAlias = optString("credentialAlias", "endpoint-api-key"),
    )

    private fun secretKey(endpointId: String, alias: String) = "secret.$endpointId.$alias"

    private companion object {
        const val PREFERENCES_NAME = "bram.remote_endpoints"
        const val ENDPOINTS_KEY = "endpoints.v1"
        const val KEYSTORE_ALIAS = "bram.endpoint_key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
