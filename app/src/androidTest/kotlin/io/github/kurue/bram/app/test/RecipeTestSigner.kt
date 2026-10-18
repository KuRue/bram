package io.github.kurue.bram.app.test

import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Ephemeral test key, never a publisher identity. */
internal class RecipeTestSigner {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun sign(index: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update("Bram recipe index v1\n".toByteArray(Charsets.UTF_8))
            update(index)
            sign()
        }
        return JSONObject().put("version", 1).put("algorithm", "SHA256withECDSA")
            .put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
            .put("signature", Base64.getEncoder().encodeToString(signature)).toString().toByteArray(Charsets.UTF_8)
    }
}
