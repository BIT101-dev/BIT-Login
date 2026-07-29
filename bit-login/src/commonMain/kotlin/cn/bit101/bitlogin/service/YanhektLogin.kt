package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.util.uriQuery

/**
 * 延河课堂 login. Mirrors Python `bit_login.service.yanhekt_login`.
 *
 * Flow:
 *  1. SSO login with yanhekt_cb as callback
 *  2. GET callback (no redirects) → redirect URL with `token=...` query
 *  3. If token present, set Authorization Bearer + Origin/Referer/Xdomain-Client
 *  4. Fail loudly if token cannot be parsed
 */
class YanhektLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val data = sso.login(username, password, callbackUrl = Config.Urls.campus.getValue("yanhekt_cb"))
        var callback = data.callback
        var token = ""

        try {
            val rLogin = sso.session.get(callback, allowRedirects = false)
            callback = rLogin.location() ?: callback
            val query = uriQuery(callback) ?: ""
            token = query.split("&")
                .firstOrNull { it.startsWith("token=") }
                ?.substringAfter("=")
                .orEmpty()
            if (token.isEmpty() && "token=" in callback) {
                token = callback.substringAfter("token=").substringBefore("&")
            }
            sso.session.headers.apply {
                put("Authorization", "Bearer $token")
                put("Origin", "https://www.yanhekt.cn")
                put("Referer", "https://www.yanhekt.cn/")
                put("Xdomain-Client", "web_user")
            }
        } catch (_: Throwable) {
            // Swallow per Python; the explicit token check below handles failure.
        }

        if (token.isEmpty()) throw LoginError("Yanhekt Token 解析失败")

        return buildJsonObject {
            put("token", JsonPrimitive(token))
            put("cookie_json", buildJsonObject {
                data.cookieJson.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
            put("cookie", JsonPrimitive(data.cookie))
        }
    }
}
