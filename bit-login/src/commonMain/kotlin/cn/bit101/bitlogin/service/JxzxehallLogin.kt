package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.util.PythonUrlEncoding

/**
 * 教学中心登录落地应用前缀: 研究生(学号31/32)用wdkbby, 其余用xsfacx.
 * xsfacx(培养方案查询)对研究生返回403, 导致教学中心应用会话无法建立;
 * wdkbby(我的课表)对本科生与研究生均开放, 课表三接口两类学生同源.
 */
internal fun landingAppPrefix(username: String): String =
    if (username.length == 10 && username.all { it.isDigit() } &&
        (username.startsWith("31") || username.startsWith("32"))
    ) "jxzxehall_wdkbby_" else "jxzxehall_"

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
        val prefix = landingAppPrefix(username)
        // Preflight uses a fresh client to avoid carrying webvpn cookies; matches Python's
        // use of `requests.get(...)` outside the session.
        val preflightClient = HttpClient()
        val rPre = preflightClient.get(
            Config.Urls.campus.getValue(prefix + "auth"),
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
        sso.session.get(activeUrl(prefix + "app_base"))
        sso.session.get(activeUrl(prefix + "config"), headers = headers)

        return cookiesResult()
    }
}
