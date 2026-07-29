package cn.bit101.bitlogin.server.auth

import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RegistrationTokenTest {
    @TempDir lateinit var tempDir: java.nio.file.Path

    private fun generateKey(): Triple<String, ByteArray, ByteArray> {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder().encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        return Triple(pem, keyPair.public.encoded, keyPair.private.encoded)
    }

    private fun base64urlDecode(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.getUrlDecoder().decode(padded)
    }

    @Test
    fun `issues verifiable Ed25519 JWT`() {
        val (pem, publicKey, _) = generateKey()
        val keyPath = tempDir.resolve("key.pem")
        Files.writeString(keyPath, pem)
        val env = mapOf(
            "REGISTRATION_JWT_PRIVATE_KEY_FILE" to keyPath.toString(),
            "REGISTRATION_JWT_ALLOWED_AUDIENCES" to "https://app.example,https://other.example",
            "REGISTRATION_JWT_ISSUER" to "bit-login-test",
            "REGISTRATION_JWT_KEY_ID" to "test-key-1",
            "REGISTRATION_JWT_TTL" to "120",
        )
        val (token, ttl) = RegistrationToken.issue("20210001", "ch-abc", "https://app.example", env)
        assertEquals(120, ttl)
        val parts = token.split(".")
        assertEquals(3, parts.size)
        val header = Json.parseToJsonElement(String(base64urlDecode(parts[0]))).jsonObject
        assertEquals("EdDSA", header["alg"]!!.jsonPrimitive.content)

        // exp/iat MUST be JSON numbers (RFC 7519 NumericDate), not strings.
        val payload = Json.parseToJsonElement(String(base64urlDecode(parts[1]))).jsonObject
        assertFalse(payload["exp"]!!.jsonPrimitive.isString, "exp must be a numeric JSON value")
        assertFalse(payload["iat"]!!.jsonPrimitive.isString, "iat must be a numeric JSON value")
        assertEquals("registration", payload["purpose"]!!.jsonPrimitive.content)

        val signingInput = "${parts[0]}.${parts[1]}"
        val signature = base64urlDecode(parts[2])
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKey)))
        verifier.update(signingInput.toByteArray(Charsets.US_ASCII))
        assertTrue(verifier.verify(signature), "JWT signature must verify with the matching public key")
    }

    @Test
    fun `rejects disallowed audience`() {
        val (pem, _, _) = generateKey()
        val keyPath = tempDir.resolve("key.pem")
        Files.writeString(keyPath, pem)
        val env = mapOf(
            "REGISTRATION_JWT_PRIVATE_KEY_FILE" to keyPath.toString(),
            "REGISTRATION_JWT_ALLOWED_AUDIENCES" to "https://app.example",
        )
        assertThrows(RegistrationAudienceError::class.java) {
            RegistrationToken.issue("user", "ch", "https://evil.example", env)
        }
    }

    @Test
    fun `rejects missing key file`() {
        assertThrows(RegistrationTokenError::class.java) {
            RegistrationToken.issue("user", "ch", "aud", emptyMap())
        }
    }

    @Test
    fun `rejects zero TTL`() {
        val (pem, _, _) = generateKey()
        val keyPath = tempDir.resolve("key.pem")
        Files.writeString(keyPath, pem)
        val env = mapOf(
            "REGISTRATION_JWT_PRIVATE_KEY_FILE" to keyPath.toString(),
            "REGISTRATION_JWT_ALLOWED_AUDIENCES" to "https://app.example",
            "REGISTRATION_JWT_TTL" to "0",
        )
        assertThrows(RegistrationTokenError::class.java) {
            RegistrationToken.issue("user", "ch", "https://app.example", env)
        }
    }
}
