package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.PostContext
import cn.bit101.bitlogin.http.PostInterceptor
import cn.bit101.bitlogin.login.SsoLogin

/**
 * 大创系统 (CXCY) login. Mirrors Python `bit_login.service.cxcy_login`.
 *
 * Flow:
 *  1. SSO login with cxcy_cas as callback
 *  2. GET callback (no redirects) → location
 *  3. GET location (with redirects) → final session established
 *  4. GET cxcy_main
 *  5. Install a postInterceptor that injects `__RequestVerificationToken` cookie
 *    into every POST to cxcy.bit.edu.cn (header + form field)
 */
class CxcyLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val data = sso.login(username, password, callbackUrl = Config.Urls.campus.getValue("cxcy_cas"))
        val rLogin = sso.session.get(data.callback, allowRedirects = false)
        val nextLocation = rLogin.location() ?: data.callback
        sso.session.get(nextLocation, allowRedirects = true)
        sso.session.get(Config.Urls.campus.getValue("cxcy_main"))

        // Merge cxcy headers into session defaults.
        Config.Headers.cxcy.forEach { (k, v) -> sso.session.headers[k] = v }

        // Install token injector.
        sso.session.postInterceptor = CxcyPostInterceptor(sso.session)

        // Trigger one POST to populate state (Python does this for diagnostic reasons).
        sso.session.post("http://cxcy.bit.edu.cn/pt/System/Platform/GetEffectivePlatformList")

        return cookiesResult()
    }

    private class CxcyPostInterceptor(private val session: cn.bit101.bitlogin.http.HttpClient) : PostInterceptor {
        override suspend fun intercept(ctx: PostContext) {
            if (!ctx.url.contains("cxcy.bit.edu.cn")) return
            val token = session.cookieValue("__RequestVerificationToken_L3B00") ?: return
            ctx.headers["__RequestVerificationToken"] = token
            when {
                ctx.formData != null -> ctx.formData!!["__RequestVerificationToken"] = token
                ctx.json == null -> ctx.formData = linkedMapOf("__RequestVerificationToken" to token)
            }
        }
    }
}
