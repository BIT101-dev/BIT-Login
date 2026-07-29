package cn.bit101.bitlogin.sso

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

actual object Crypto {
    private val base64 = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()
    private val secureRandom = SecureRandom()
    private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    actual fun encryptAesBase64(plaintext: String, encodedKey: String): String {
        val key = decodeAesKey(encodedKey)
        return base64.encodeToString(aes(Cipher.ENCRYPT_MODE, key).doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    actual fun protectedCsrfHeaders(): Map<String, String> = protectedCsrfHeaders(secureRandom)

    /** JVM-only overload allowing a deterministic [SecureRandom] for tests. */
    fun protectedCsrfHeaders(random: SecureRandom): Map<String, String> {
        val key = buildString(32) {
            repeat(32) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) }
        }
        val encoded = base64.encodeToString(key.toByteArray(Charsets.US_ASCII))
        val midpoint = encoded.length / 2
        val mixed = encoded.substring(0, midpoint) + encoded + encoded.substring(midpoint)
        return mapOf(
            "Csrf-Key" to key,
            "Csrf-Value" to digest("MD5", mixed.toByteArray(Charsets.US_ASCII)),
        )
    }

    actual fun encryptUrlCryptoBody(value: JsonElement, publicKeyPem: String): EncryptedUrlCryptoBody {
        val aesKey = ByteArray(16).also(secureRandom::nextBytes)
        val plaintext = Json.encodeToString(JsonElement.serializer(), value).toByteArray(Charsets.UTF_8)
        val encrypted = aes(Cipher.ENCRYPT_MODE, aesKey).doFinal(plaintext)
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, publicKey(publicKeyPem))
        val encryptedKey = rsa.doFinal(base64.encode(aesKey))
        return EncryptedUrlCryptoBody(
            body = base64.encodeToString(encrypted),
            encryptedKey = base64.encodeToString(encryptedKey),
            aesKey = aesKey,
        )
    }

    actual fun decryptUrlCryptoResponse(value: String, aesKey: ByteArray): JsonElement {
        require(aesKey.size in setOf(16, 24, 32)) { "AES key must be 16, 24, or 32 bytes" }
        var current = value
        for (round in 0 until 4) {
            val parsed = parseJsonOrNull(current)
            if (parsed != null) {
                val stringValue = (parsed as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (stringValue == null) return parsed
                if (stringValue != current) {
                    current = stringValue
                    continue
                }
            }
            current = runCatching {
                val cipherText = base64Decoder.decode(current)
                String(aes(Cipher.DECRYPT_MODE, aesKey).doFinal(cipherText), Charsets.UTF_8)
            }.getOrElse { return kotlinx.serialization.json.JsonPrimitive(current) }
        }
        return parseJsonOrNull(current) ?: kotlinx.serialization.json.JsonPrimitive(current)
    }

    actual fun sha256(value: ByteArray): String = digest("SHA-256", value)

    private fun decodeAesKey(encodedKey: String): ByteArray = try {
        base64Decoder.decode(encodedKey)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("croypto is not valid Base64", error)
    }.also { require(it.size in setOf(16, 24, 32)) { "croypto must decode to a valid AES key" } }

    private fun aes(mode: Int, key: ByteArray): Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key, "AES"))
    }

    private fun publicKey(pem: String) = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(
            base64Decoder.decode(pem.replace(Regex("-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----|\\s"), "")),
        ),
    )

    private fun digest(algorithm: String, value: ByteArray): String = MessageDigest.getInstance(algorithm)
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun parseJsonOrNull(value: String): JsonElement? {
        val trimmed = value.trimStart()
        if (trimmed.isEmpty() || trimmed.first() !in "\"[{0123456789-tfn") return null
        return runCatching { Json.parseToJsonElement(value) }.getOrNull()
    }
}
