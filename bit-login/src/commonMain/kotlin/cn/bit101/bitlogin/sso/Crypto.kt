package cn.bit101.bitlogin.sso

import kotlinx.serialization.json.JsonElement

/**
 * AES/RSA/CSRF crypto, byte-for-byte port of Python `bit_login/sso/crypto.py`.
 *
 * The implementation uses JDK crypto (`javax.crypto` / `java.security`) and is
 * therefore kept in the shared JVM source set; only the signatures live in
 * common so that common code (BitSsoClient, Fingerprint) can call them.
 */
expect object Crypto {
    fun encryptAesBase64(plaintext: String, encodedKey: String): String

    fun protectedCsrfHeaders(): Map<String, String>

    fun encryptUrlCryptoBody(value: JsonElement, publicKeyPem: String): EncryptedUrlCryptoBody

    fun decryptUrlCryptoResponse(value: String, aesKey: ByteArray): JsonElement

    fun sha256(value: ByteArray): String
}

data class EncryptedUrlCryptoBody(
    val body: String,
    val encryptedKey: String,
    val aesKey: ByteArray,
)
