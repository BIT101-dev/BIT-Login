package cn.bit101.bitlogin.sso

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CryptoTest {
    private val aesKey = "MDEyMzQ1Njc4OWFiY2RlZg=="

    @Test
    fun `AES encryption matches Python protocol vector`() {
        assertEquals("R4lDIBO/32oRLZSjtsPrGQ==", Crypto.encryptAesBase64("password", aesKey))
    }

    @Test
    fun `CSRF headers use the page mixing algorithm`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val headers = Crypto.protectedCsrfHeaders(random)
        val key = headers.getValue("Csrf-Key")
        val encoded = Base64.getEncoder().encodeToString(key.toByteArray(Charsets.US_ASCII))
        val mixed = encoded.substring(0, encoded.length / 2) + encoded + encoded.substring(encoded.length / 2)
        assertEquals(32, key.length)
        assertEquals(headers["Csrf-Value"], java.security.MessageDigest.getInstance("MD5").digest(mixed.toByteArray()).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `response unwrapping handles encrypted JSON`() {
        val key = Base64.getDecoder().decode(aesKey)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal("{\"ok\":true}".toByteArray()))
        assertEquals(Json.parseToJsonElement("{\"ok\":true}"), Crypto.decryptUrlCryptoResponse(encrypted, key))
    }

    @Test
    fun `response unwrapping stops after four layers`() {
        var value = "value"
        repeat(5) { value = JsonPrimitive(value).toString() }
        assertTrue(Crypto.decryptUrlCryptoResponse(value, ByteArray(16)).toString().isNotEmpty())
    }

    @Test
    fun `invalid AES key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Crypto.encryptAesBase64("x", "not base64!") }
        assertThrows(IllegalArgumentException::class.java) { Crypto.encryptAesBase64("x", Base64.getEncoder().encodeToString(ByteArray(15))) }
    }
}
