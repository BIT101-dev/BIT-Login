package cn.bit101.bitlogin.server.auth

import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

open class RegistrationTokenError(message: String) : Exception(message)
class RegistrationAudienceError(message: String) : RegistrationTokenError(message)

object RegistrationToken {
    private val base64url = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getDecoder()
    private val random = SecureRandom()

    fun issue(subject: String, challengeId: String, audience: String, env: Map<String, String> = System.getenv()): Pair<String, Int> {
        val keyFile = (env["REGISTRATION_JWT_PRIVATE_KEY_FILE"] ?: "").trim()
        if (keyFile.isBlank()) throw RegistrationTokenError("REGISTRATION_JWT_PRIVATE_KEY_FILE is not configured")
        val privateKey = privateKeyFor(Paths.get(keyFile).expanduser())
        val ttl = (env["REGISTRATION_JWT_TTL"] ?: "300").toIntOrNull()
            ?: throw RegistrationTokenError("REGISTRATION_JWT_TTL must be an integer")
        if (ttl < 1) throw RegistrationTokenError("REGISTRATION_JWT_TTL must be positive")

        val allowedAudiences = (env["REGISTRATION_JWT_ALLOWED_AUDIENCES"] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (allowedAudiences.isEmpty()) throw RegistrationTokenError("REGISTRATION_JWT_ALLOWED_AUDIENCES is not configured")
        val aud = audience.trim()
        if (aud !in allowedAudiences) throw RegistrationAudienceError("registration JWT audience is not allowed")

        val sub = subject.trim()
        if (sub.isEmpty()) throw RegistrationTokenError("registration subject is empty")

        val now = System.currentTimeMillis() / 1000
        val kid = env["REGISTRATION_JWT_KEY_ID"] ?: "registration-1"
        val issuer = env["REGISTRATION_JWT_ISSUER"] ?: "bit-login"

        val header = linkedMapOf(
            "alg" to "EdDSA",
            "kid" to kid,
            "typ" to "JWT",
        )
        val payload = linkedMapOf(
            "aud" to aud,
            "exp" to (now + ttl),
            "iat" to now,
            "iss" to issuer,
            "jti" to challengeId,
            "purpose" to "registration",
            "sub" to sub,
        )

        val signingInput = "${jsonPart(header)}.${jsonPart(payload)}"
        val signature = Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "${signingInput}.${base64url.encodeToString(signature)}" to ttl
    }

    private class KeyCache(val path: java.nio.file.Path, val mtime: Long, val size: Long, val key: PrivateKey)

    private val cacheLock = Any()
    private var keyCache: KeyCache? = null

    // Cache the parsed key across issue() calls; invalidated when the key
    // file's path, mtime, or size changes. Any failure maps to the same error
    // as before: "registration JWT private key is invalid".
    private fun privateKeyFor(path: java.nio.file.Path): PrivateKey {
        val stat = try {
            Files.getLastModifiedTime(path).toMillis() to Files.size(path)
        } catch (_: Exception) {
            throw RegistrationTokenError("registration JWT private key is invalid")
        }
        synchronized(cacheLock) {
            keyCache?.let { if (it.path == path && it.mtime == stat.first && it.size == stat.second) return it.key }
        }
        val key = try {
            loadPrivateKey(path)
        } catch (_: Exception) {
            throw RegistrationTokenError("registration JWT private key is invalid")
        }
        synchronized(cacheLock) { keyCache = KeyCache(path, stat.first, stat.second, key) }
        return key
    }

    private fun loadPrivateKey(path: java.nio.file.Path): PrivateKey {
        val pem = Files.readString(path)
        val der = base64Decoder.decode(
            pem.replace("-----BEGIN PRIVATE KEY-----", "")
               .replace("-----END PRIVATE KEY-----", "")
               .replace(Regex("\\s"), "")
        )
        return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun jsonPart(fields: Map<String, Any>): String {
        val json = kotlinx.serialization.json.buildJsonObject {
            fields.toSortedMap().forEach { (k, v) ->
                // Preserve JSON value types: numbers and booleans must not be
                // stringified, otherwise JWT claims like `exp`/`iat` violate the
                // NumericDate requirement (RFC 7519 §2) and the signature
                // diverges from the Python reference.
                put(k, toJsonElement(v))
            }
        }
        val compact = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), json
        )
        return base64url.encodeToString(compact.toByteArray(Charsets.UTF_8))
    }

    private fun toJsonElement(v: Any): kotlinx.serialization.json.JsonElement =
        when (v) {
            is Number -> kotlinx.serialization.json.JsonPrimitive(v)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
            is String -> kotlinx.serialization.json.JsonPrimitive(v)
            else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
        }

    private fun java.nio.file.Path.expanduser(): java.nio.file.Path =
        if (this.toString().startsWith("~")) Paths.get(System.getProperty("user.home") + this.toString().substring(1))
        else this
}
