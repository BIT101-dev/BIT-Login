package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.util.PythonUrlEncoding

/**
 * 教学中心/一站式大厅 login. Mirrors Python `bit_login.service.jxzxehall_login`.
 *
 * Flow:
 *  1. Preflight GET campus jxzxehall_auth (no redirects) → Location?service=...
 *  2. SSO login with the decoded service URL
 *  3. WebVPN patch (rewrites callback + swaps session)
 *  4. GET callback, GET app base, GET config
 */
class JxzxehallLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val headers = Config.Headers.jxzxehall
        // Preflight uses a fresh client to avoid carrying webvpn cookies; matches Python's
        // use of `requests.get(...)` outside the session.
        val preflightClient = HttpClient()
        val rPre = preflightClient.get(
            Config.Urls.campus.getValue("jxzxehall_auth"),
            headers = headers,
            allowRedirects = false,
        )
        preflightClient.close()

        val location = rPre.location() ?: throw LoginError("jxzxehall: 缺少 Location header")
        val rawService = location.substringAfter("?service=", "")
        if (rawService.isEmpty()) throw LoginError("jxzxehall: 解析 service url 失败")
        val callbackUrl = PythonUrlEncoding.unquote(rawService)

        var res = sso.login(username, password, callbackUrl = callbackUrl)
        val patchedCallback = patchWebvpn(username, password, res.callback)

        sso.session.get(patchedCallback, headers = headers)
        sso.session.get(activeUrl("jxzxehall_app_base"))
        sso.session.get(activeUrl("jxzxehall_config"), headers = headers)

        return cookiesResult()
    }
}
