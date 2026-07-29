package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.login.SsoLogin

/** WebVPN login. Mirrors Python `bit_login.service.webvpn_login`. */
class WebVpnLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val res = sso.login(
            username, password,
            callbackUrl = Config.Urls.webvpn.getValue("webvpn_cb"),
            webvpnMode = true,
        )
        val callback = if (res.callback.startsWith("/")) {
            Config.Urls.webvpn.getValue("webvpn_origin") + res.callback
        } else {
            res.callback
        }
        sso.session.get(callback, allowRedirects = true)
        return buildJsonObject {
            put("cookie_json", buildJsonObject {
                res.cookieJson.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
            put("cookie", JsonPrimitive(res.cookie))
            put("callback", JsonPrimitive(callback))
        }
    }
}
