package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.util.uriQuery

/**
 * iBIT login. Mirrors Python `bit_login.service.ibit_login`.
 *
 * Flow:
 *  1. SSO login with ibit_cb as callback
 *  2. GET callback (no redirects) → redirect URL with `badgeFromPc=...` query
 *  3. If badge present, set Badge / badge / Xdomain-Client / Referer headers
 */
class IbitLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val data = sso.login(username, password, callbackUrl = Config.Urls.campus.getValue("ibit_cb"))
        var callback = data.callback
        val cookieJson = data.cookieJson.toMutableMap()

        try {
            val rLogin = sso.session.get(callback, allowRedirects = false)
            callback = rLogin.location() ?: callback
            val query = uriQuery(callback) ?: ""
            val badge = query.split("&")
                .firstOrNull { it.startsWith("badgeFromPc=") }
                ?.substringAfter("=")
                .orEmpty()
            if (badge.isNotEmpty()) {
                cookieJson["badge_2"] = badge
                sso.session.headers.apply {
                    put("Badge", badge)
                    put("badge", badge)
                    put("Xdomain-Client", "web_user")
                    put("Referer", "https://ibit.yanhekt.cn/desktop?badgeFromPc=$badge")
                }
            }
        } catch (_: Throwable) {
            // Swallow per Python `except Exception: pass`.
        }

        return buildJsonObject {
            put("cookie_json", buildJsonObject {
                cookieJson.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
            put("cookie", JsonPrimitive(cookieJson.entries.joinToString("; ") { "${it.key}=${it.value}" }))
            put("callback", JsonPrimitive(callback))
        }
    }
}
