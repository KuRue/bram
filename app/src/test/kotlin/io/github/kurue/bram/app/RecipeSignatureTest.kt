package io.github.kurue.bram.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class RecipeSignatureTest {
    private val index = "{\"revision\":\"one\"}\n".toByteArray()
    private fun refused(bytes: ByteArray) { assertTrue(runCatching { RecipeSignature.verify(index, bytes) }.isFailure) }

    @Test fun `public OpenSSL vector verifies and whitespace belongs to the signed payload`() {
        val folder = File("src/androidTest/assets/recipe-signing")
        val exact = File(folder, "index.json").readBytes()
        val proof = File(folder, "index.signature.json").readBytes()
        val verified = RecipeSignature.verify(exact, proof)
        assertEquals("17c23896551d3644bbb81ff39ec56723f53ae73c046a5abbb9caec5655553b78", verified.fingerprint)
        assertTrue(runCatching { RecipeSignature.verify(exact + '\n'.code.toByte(), proof) }.isFailure)
    }

    @Test fun `signature algorithm version unknown fields and alternate Base64 encodings are refused`() {
        val valid = JSONObject(String(RecipeTestSigner().sign(index)))
        listOf(
            JSONObject(valid.toString()).put("algorithm", "SHA1withECDSA"),
            JSONObject(valid.toString()).put("version", 2),
            JSONObject(valid.toString()).put("version", "1"),
            JSONObject(valid.toString()).put("ignored", true),
            JSONObject(valid.toString()).put("publicKey", valid.getString("publicKey").trimEnd('=')),
            JSONObject(valid.toString()).put("signature", "AAAA"),
        ).forEach { refused(it.toString().toByteArray()) }
    }

    @Test fun `key substitution and signature corruption fail cryptographic verification`() {
        val valid = JSONObject(String(RecipeTestSigner().sign(index)))
        val other = JSONObject(String(RecipeTestSigner().sign(index)))
        refused(JSONObject(valid.toString()).put("publicKey", other.getString("publicKey")).toString().toByteArray())
        val corrupt = Base64.getDecoder().decode(valid.getString("signature"))
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        refused(valid.put("signature", Base64.getEncoder().encodeToString(corrupt)).toString().toByteArray())
    }

    @Test fun `a valid P384 signature is outside this protocol`() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(RecipeSignature.DOMAIN.toByteArray()); update(index); sign()
        }
        refused(JSONObject().put("version", 1).put("algorithm", "SHA256withECDSA")
            .put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
            .put("signature", Base64.getEncoder().encodeToString(sig)).toString().toByteArray())
    }

    @Test fun `valid signatures made without the protocol context are refused`() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val rawSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(index); sign()
        }
        refused(JSONObject().put("version", 1).put("algorithm", "SHA256withECDSA")
            .put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
            .put("signature", Base64.getEncoder().encodeToString(rawSignature)).toString().toByteArray())
    }

    @Test fun `truncated malformed UTF8 and oversized proofs are refused`() {
        listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), ByteArray(4097), byteArrayOf(), "{".toByteArray()).forEach(::refused)
    }
}
