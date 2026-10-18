package io.github.kurue.bram.app

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Detached proof over exact index bytes; recipe hashes in the index bind the other documents. */
data class RecipeSignature(val publicKey: String, val signature: String) {
    val fingerprint: String get() = recipeSha256(Base64.getDecoder().decode(publicKey))
    fun json(): String = JSONObject().put("version", 1).put("algorithm", "SHA256withECDSA")
        .put("publicKey", publicKey).put("signature", signature).toString()

    companion object {
        const val FILE_NAME = "index.signature.json"
        const val MAX_BYTES = 4096
        const val DOMAIN = "Bram recipe index v1\n"
        // DER SubjectPublicKeyInfo: id-ecPublicKey + named prime256v1 + uncompressed point.
        private val P256_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun verify(indexBytes: ByteArray, signatureBytes: ByteArray): RecipeSignature {
            require(signatureBytes.size in 1..MAX_BYTES) { "Recipe signature is empty or too large" }
            val json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(signatureBytes)).toString()
            val obj = JSONObject(json)
            require(obj.keys().asSequence().toSet() == setOf("version", "algorithm", "publicKey", "signature")) {
                "Recipe signature must contain only version, algorithm, publicKey and signature"
            }
            require(obj.get("version") == 1 && obj.get("algorithm") == "SHA256withECDSA") {
                "Unsupported recipe signature version or algorithm"
            }
            fun bytes(name: String, limit: Int): ByteArray {
                val value = obj.get(name)
                require(value is String && value.length <= limit) { "Invalid recipe signature $name" }
                val decoded = Base64.getDecoder().decode(value)
                require(Base64.getEncoder().encodeToString(decoded) == value) { "Recipe signature $name must use canonical Base64" }
                return decoded
            }
            val public = bytes("publicKey", 128)
            require(public.size == 91 && public.take(P256_PREFIX.size).toByteArray().contentEquals(P256_PREFIX)) {
                "Recipe signing key must be a named P-256 uncompressed DER public key"
            }
            val signature = bytes("signature", 96)
            require(signature.size in 8..72) { "Invalid recipe signature length" }
            val valid = runCatching {
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(DOMAIN.toByteArray(StandardCharsets.UTF_8))
                    update(indexBytes)
                    verify(signature)
                }
            }.getOrDefault(false)
            require(valid) { "Recipe index signature is invalid; accepted snapshot kept" }
            return RecipeSignature(obj.getString("publicKey"), obj.getString("signature"))
        }
    }
}
