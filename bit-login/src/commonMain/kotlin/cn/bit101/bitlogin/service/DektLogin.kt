package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.login.SsoLogin

/**
 * 第二课堂 login. Mirrors Python `bit_login.service.dekt_login`.
 * Note: Python marks this as "尚不可用" (not yet usable).
 */
class DektLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {
    override suspend fun doLogin(username: String, password: String): JsonObject {
        val data = sso.login(username, password, callbackUrl = Config.Urls.campus.getValue("dekt_cb"))
        sso.session.get(data.callback, allowRedirects = true)
        return cookiesResult()
    }
}
