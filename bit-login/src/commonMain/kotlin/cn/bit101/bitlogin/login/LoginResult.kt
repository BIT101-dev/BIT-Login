package cn.bit101.bitlogin.login

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Result of an SSO login flow. Equivalent to the dict returned by
 * Python `bit_login.login.Login.login(...)`:
 *   { cookie_json: {..}, cookie: "k=v; k=v", callback: "https://..." }
 */
class LoginResult(
    val cookieJson: Map<String, String>,
    val callback: String,
    val ticket: String? = null,
) {
    val cookie: String = cookieJson.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun toJson(): JsonObject = buildJsonObject {
        put("cookie_json", buildJsonObject { cookieJson.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        put("cookie", JsonPrimitive(cookie))
        put("callback", JsonPrimitive(callback))
    }
}
