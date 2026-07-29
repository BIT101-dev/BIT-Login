package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpResponse
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.LoginResult
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.util.PythonUrlEncoding

/**
 * 教务系统 (JWB) login. Mirrors Python `bit_login.service.jwb_login`.
 *
 * Flow:
 *  1. SSO login with jwb_cb as callback
 *  2. (webvpn mode) swap session via patch_webvpn
 *  3. Disable trust_env (proxy) and set jwb headers
 *  4. Follow up to 4 manual redirects (preflight → callback → landing → main)
 *  5. Validate final URL is not on sso.bit.edu.cn/cas/login
 */
class JwbLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    private suspend fun requestLoginStep(label: String, url: String, headers: Map<String, String>): HttpResponse {
        val response = sso.session.get(url, headers = headers, allowRedirects = false)
        if (response.status >= 500) {
            throw LoginError("jwb: $label 失败 HTTP ${response.status}")
        }
        return response
    }

    override suspend fun doLogin(username: String, password: String): JsonObject {
        // 1. SSO login (campus callback, no webvpn rewriting yet)
        var res: LoginResult = sso.login(
            username, password,
            callbackUrl = Config.Urls.campus.getValue("jwb_cb"),
        )

        // 2. WebVPN patch (rewrites callback URL + swaps session)
        res = LoginResult(
            cookieJson = res.cookieJson,
            callback = patchWebvpn(username, password, res.callback),
        )

        // 3. Headers
        val headers = Config.Headers.jwb.toMutableMap()
        val ssoLoginUi = Config.Urls.Base.SSO_LOGIN_UI
        val jwbCb = Config.Urls.campus.getValue("jwb_cb")
        headers["Referer"] = "$ssoLoginUi?service=${PythonUrlEncoding.quote(jwbCb)}"
        headers.forEach { (k, v) -> sso.session.headers[k] = v }

        // 4. Multi-step redirects
        var response = requestLoginStep("preflight-root", activeUrl("jwb_cb"), headers)
        response = requestLoginStep("callback", res.callback, headers)
        response.location()?.let { loc ->
            response = requestLoginStep("landing", resolveRelative(response.url, loc), headers)
        }
        response.location()?.let { loc ->
            response = requestLoginStep("login-to-xk", resolveRelative(response.url, loc), headers)
        }
        response.location()?.let { loc ->
            response = requestLoginStep("main", resolveRelative(response.url, loc), headers)
        }

        if (response.url.contains("sso.bit.edu.cn/cas/login")) {
            throw LoginError("jwb: 登录回调后仍停留在统一身份认证页")
        }

        return cookiesResult()
    }

    private fun resolveRelative(base: String, location: String): String =
        PythonUrlEncoding.urlJoin(base, location)
}
