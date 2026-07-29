package cn.bit101.bitlogin.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.NetworkEnv
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.login.LoginResult
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.util.WebVpnUrl

/**
 * Abstract base class for all service-specific login flows.
 * Mirrors Python `bit_login.service.BaseLogin`.
 *
 * Subclasses implement [doLogin] returning a JSON-serialisable result (usually
 * the result of [cookiesResult]). [login] returns `this` for chaining.
 */
abstract class BaseLogin(
    protected val sso: SsoLogin = SsoLogin(),
) {
    @Volatile private var initialized = false
    private var result: JsonObject = buildJsonObject {}

    init {
        // Ensure network environment is determined before any service login.
        // Runs lazily in coroutine context when first awaited.
    }

    /** Subclass-specific login flow. Should call [cookiesResult] (or build a richer dict). */
    protected abstract suspend fun doLogin(username: String, password: String): JsonObject

    /** Run [doLogin]; populate [getResult]. Returns this for chaining. */
    suspend fun login(username: String, password: String): BaseLogin {
        NetworkEnv.ensureInitialized()
        result = doLogin(username, password)
        initialized = true
        return this
    }

    fun getSession(): HttpClient {
        check(initialized) { "未登录!" }
        return sso.session
    }

    fun getResult(): JsonObject {
        check(initialized) { "未登录!" }
        return result
    }

    /** Common cookie-only result, equivalent to Python `_get_cookies_result()`. */
    protected fun cookiesResult(): JsonObject {
        val cookies = sso.session.cookieMap()
        return buildJsonObject {
            put("cookie_json", buildJsonObject {
                cookies.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
            put("cookie", JsonPrimitive(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }))
        }
    }

    /**
     * In WebVPN mode, after SSO login we must establish a webvpn session and
     * swap the underlying HttpClient so subsequent requests go through webvpn.
     * Returns the (possibly rewritten) callback URL.
     */
    protected suspend fun patchWebvpn(username: String, password: String, callback: String): String {
        if (!NetworkEnv.webvpnMode) return callback
        val rewritten = WebVpnUrl.convertToWebvpnUrl(callback)
        val webvpn = WebVpnLogin(SsoLogin(
            session = sso.session,
            captchaSolver = sso.captchaSolver,
            smsCodeCallback = sso.smsCodeCallback,
        ))
        webvpn.login(username, password)
        sso.session = webvpn.getSession()
        return rewritten
    }
}

/** Resolve the active URL by key from Config.Urls.active (populated by NetworkEnv). */
internal fun activeUrl(key: String): String =
    Config.Urls.active[key] ?: throw LoginError("URL not configured: $key")
