package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import cn.bit101.bitlogin.http.HttpResponse
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.util.PythonUrlEncoding

/**
 * 教务系统成绩单 (JWB CJD) login. Mirrors Python `bit_login.service.jwb_cjd_login`.
 *
 * Flow:
 *  1. GET ExternalLogin (no redirect following) → Location header with service=...
 *  2. SSO login using the decoded service URL as callback
 *  3. GET callback URL to establish session
 */
class JwbCjdLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val r1: HttpResponse = sso.session.get(
            "https://jwb.bit.edu.cn/cjd/Account/ExternalLogin",
            allowRedirects = false,
        )
        val location = r1.location()
            ?: throw LoginError("jwb_cjd: 缺少 Location header")
        val rawService = location.substringAfter("?service=", "")
        if (rawService.isEmpty()) throw LoginError("jwb_cjd: 解析 service url 失败")
        val callbackUrl = PythonUrlEncoding.unquote(rawService)

        val res = sso.login(username, password, callbackUrl = callbackUrl)
        sso.session.get(res.callback)

        return cookiesResult()
    }
}
