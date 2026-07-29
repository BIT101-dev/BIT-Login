package cn.bit101.bitlogin.server.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.server.auth.AuthWorker
import cn.bit101.bitlogin.server.auth.ChallengeError
import cn.bit101.bitlogin.server.auth.ChallengeStore
import cn.bit101.bitlogin.server.plugins.HttpException
import cn.bit101.bitlogin.service.BaseLogin

/**
 * Mirrors Python `_resolve_service_session`. Resolves a downstream service
 * session either from a Bearer challenge token or via username/password.
 *
 * Bearer path: validate token + challenge_id, return the stored session.
 *
 * Password path: start a background authentication worker, wait up to 1 second.
 * If login completes, return the session. If not, throw HttpException(202) with
 * the challenge snapshot so the client can poll `/api/auth/{id}`.
 */
class AuthServiceExecutor(
    private val challengeStore: ChallengeStore,
    private val authWorker: AuthWorker,
) {
    suspend fun <T> execute(
        authorization: String?,
        challengeId: String?,
        username: String,
        password: String,
        serviceName: String,
        loginFactory: () -> BaseLogin,
        serviceCall: suspend (HttpClient) -> T,
    ): T {
        val session = resolveServiceSession(authorization, challengeId, username, password, serviceName)
        return serviceCall(session)
    }

    private suspend fun resolveServiceSession(
        authorization: String?,
        challengeId: String?,
        username: String,
        password: String,
        serviceName: String,
    ): HttpClient {
        if (authorization != null) {
            if (authorization.isBlank()) {
                throw HttpException(401, "missing or malformed Authorization header")
            }
            val token = extractBearerToken(authorization)
            if (challengeId.isNullOrBlank()) {
                throw HttpException(400, "challenge_id is required when using a Bearer challenge")
            }
            return try {
                challengeStore.getSession(challengeId, token, serviceName)
            } catch (e: ChallengeError) {
                throw HttpException(409, e.message ?: "service not ready")
            }
        }
        // Password path — matches Python `_resolve_service_session`.
        if (username.isBlank() || password.isEmpty()) {
            throw HttpException(400, "username/password or an authenticated Bearer challenge is required")
        }
        val handle = authWorker.startAuthentication(username, password, listOf(serviceName), username.trim())
        challengeStore.waitUntilActionable(handle.challengeId, handle.accessToken, 1_000)
        val snapshot = challengeStore.snapshot(handle.challengeId, handle.accessToken, includeAccessToken = true)
        if (snapshot["status"] != "authenticated") {
            throw HttpException(202, "pending", jsonBody = mapToJsonElement(snapshot))
        }
        return try {
            challengeStore.getSession(handle.challengeId, handle.accessToken, serviceName)
        } catch (e: ChallengeError) {
            throw HttpException(500, e.message ?: "internal error")
        }
    }

    private fun extractBearerToken(header: String): String {
        if (!header.startsWith("Bearer ")) {
            throw HttpException(401, "Bearer challenge token required")
        }
        val token = header.substring(7).trim()
        if (token.isEmpty()) throw HttpException(401, "Bearer challenge token required")
        return token
    }
}

/** Shared Map→JsonObject conversion for snapshot serialization. */
internal fun mapToJsonElement(map: Map<String, Any?>): JsonObject = buildJsonObject {
    map.forEach { (k, v) ->
        when (v) {
            is String -> put(k, v)
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, v)
            is List<*> -> put(k, JsonArray(v.mapNotNull { it?.let { JsonPrimitive(it.toString()) } }))
            null -> put(k, JsonNull)
            else -> put(k, v.toString())
        }
    }
}
