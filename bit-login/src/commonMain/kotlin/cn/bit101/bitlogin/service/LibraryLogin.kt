package cn.bit101.bitlogin.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.LoginResult
import cn.bit101.bitlogin.login.SsoLogin

/**
 * 图书馆 login. Mirrors Python `bit_login.service.library_login`.
 *
 * Flow:
 *  1. Pre-set library headers (per-call, not session-wide)
 *  2. GET lib_cas (no redirects, may fail without auth)
 *  3. SSO login with lib_cas as callback (session headers stay clean)
 *  4. GET SSO callback (no redirects) → location with `cas=...`
 *  5. Update session headers with lib headers, POST cas ticket as JSON → user info, token
 *  6. Fail if code != 1
 */
class LibraryLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val libHeaders = Config.Headers.library.toMutableMap().apply {
            put("Content-Type", Config.Common.CONTENT_TYPE_JSON)
            put("Origin", Config.Urls.campus.getValue("lib_origin"))
            put("Referer", Config.Urls.campus.getValue("lib_referer"))
        }

        val casService = Config.Urls.campus.getValue("lib_cas")
        // Seed only the library origin. Following this redirect would preload a
        // separate CAS execution and leak downstream API headers into SSO.
        try {
            sso.session.get(casService, headers = libHeaders, allowRedirects = false)
        } catch (_: Throwable) {
            // Python swallows RequestException.
        }

        val data: LoginResult = sso.login(username, password, callbackUrl = casService)

        val rLogin = sso.session.get(data.callback, headers = libHeaders, allowRedirects = false)
        var callbackUrl = rLogin.location() ?: data.callback

        var casTicket = ""
        if ("cas=" in callbackUrl) {
            casTicket = callbackUrl.substringAfter("cas=").substringBefore("&")
        } else {
            try {
                val rRetry = sso.session.get(casService, headers = libHeaders, allowRedirects = false)
                val loc = rRetry.location() ?: ""
                if ("cas=" in loc) casTicket = loc.substringAfter("cas=").substringBefore("&")
            } catch (_: Throwable) {}
        }
        if (casTicket.isEmpty()) {
            throw LoginError("图书馆登录失败: 未获取到 CAS Ticket (Url: $callbackUrl)")
        }

        // Python updates session.headers immediately before the POST; mirror that
        // so lib headers reach the auth endpoint but never the SSO login flow.
        sso.session.headers.putAll(libHeaders)
        val authResp = try {
            sso.session.post(
                Config.Urls.campus.getValue("lib_auth"),
                json = JsonObject(mapOf("cas" to JsonPrimitive(casTicket))),
            )
        } catch (e: Throwable) {
            throw LoginError("图书馆 API 解析失败: ${e.message}")
        }
        val respJson = try {
            Json.parseToJsonElement(authResp.bodyText).jsonObject
        } catch (e: Throwable) {
            throw LoginError("图书馆 API 解析失败: ${e.message}")
        }
        // Python: `resp.get("code") != 1` — integer comparison; "1" string would fail.
        val code = respJson["code"]?.jsonPrimitive
        if (code == null || code.isString || code.content.toDoubleOrNull() != 1.0) {
            val msg = respJson["msg"]?.jsonPrimitive?.content ?: ""
            throw LoginError("图书馆授权失败: $msg")
        }
        val member = respJson["member"]?.jsonObject ?: JsonObject(emptyMap())
        val cookies = sso.session.cookieMap()
        return buildJsonObject {
            put("cookie_json", buildJsonObject {
                cookies.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
            put("cookie", JsonPrimitive(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }))
            put("user_info", member)
            put("token", member["token"] ?: JsonPrimitive(""))
        }
    }
}
