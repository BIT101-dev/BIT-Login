package cn.bit101.bitlogin.login

import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.http.HttpResponse
import cn.bit101.bitlogin.sso.BitSsoClient
import cn.bit101.bitlogin.sso.BitSsoError
import cn.bit101.bitlogin.sso.CaptchaSolver
import cn.bit101.bitlogin.sso.SessionSsoTransport
import cn.bit101.bitlogin.sso.SmsCodeCallback
import cn.bit101.bitlogin.sso.SsoHttpException
import cn.bit101.bitlogin.sso.SsoLoginResult
import cn.bit101.bitlogin.util.PythonUrlEncoding
import cn.bit101.bitlogin.util.describeNetworkFailure
import cn.bit101.bitlogin.util.uriQuery

class SsoLogin(
    baseUrl: String = "",
    session: HttpClient? = null,
    captchaSolver: CaptchaSolver? = null,
    smsCodeCallback: SmsCodeCallback? = null,
) {
    var session: HttpClient = (session ?: HttpClient()).also { s ->
            // Always apply browser default headers, even on externally-provided
            // sessions. Python BitSsoClient.__init__ mutates session.headers
            // unconditionally; without a User-Agent the CAS server returns a
            // non-login page and the parser fails.
            BitSsoClient.BROWSER_DEFAULT_HEADERS.forEach { (k, v) ->
                s.headers[k] = v
            }
        }
        internal set

    private val ssoBase: String = if (baseUrl.isNotBlank() && "/cas/v1/tickets" !in baseUrl)
        baseUrl.trimEnd('/') else SSO_BASE

    val captchaSolver: CaptchaSolver? = captchaSolver
    val smsCodeCallback: SmsCodeCallback? = smsCodeCallback

    private val transport = SessionSsoTransport { this.session }

    private val client: BitSsoClient = BitSsoClient(
        baseUrl = ssoBase,
        transport = transport,
        captchaSolver = captchaSolver,
    )

    suspend fun login(
        username: String,
        password: String,
        callbackUrl: String = "",
        webvpnMode: Boolean = false,
        retries: Int = 0,
        trustDevice: Boolean = false,
        smsCodeCallback: SmsCodeCallback? = null,
        captchaSolver: CaptchaSolver? = null,
    ): LoginResult {
        if (callbackUrl.isBlank()) throw LoginError("callback_url must not be empty")
        return try {
            val result = client.loginPassword(
                username = username,
                password = password,
                service = callbackUrl,
                smsCodeCallback = smsCodeCallback ?: this.smsCodeCallback,
                captchaSolver = captchaSolver ?: this.captchaSolver,
                trustDevice = trustDevice,
                followRedirects = false,
            )
            val callback = ticketCallback(result, callbackUrl)
            LoginResult(
                cookieJson = session.cookieMap(),
                callback = callback,
                ticket = result.ticket,
            )
        } catch (e: BitSsoError) {
            // Python's login wrapper maps requests.HTTPError to one of these
            // Chinese messages based on status code. The 4xx/5xx fallthrough
            // replaces what used to be a confusing ConfigurationError when the
            // SSO server replied with an error page.
            val message = when (e) {
                is SsoHttpException -> when (e.statusCode) {
                    429 -> "统一身份认证请求过于频繁，请稍后重试"
                    in 500..599 -> "统一身份认证服务暂时不可用（HTTP ${e.statusCode}）"
                    in 400..499 -> "统一身份认证请求失败（HTTP ${e.statusCode}）"
                    else -> "统一身份认证请求失败，请稍后重试"
                }
                else -> e.message ?: "SSO error"
            }
            throw LoginError(message, e)
        } catch (e: Throwable) {
            val netMsg = describeNetworkFailure(e)
            throw LoginError(netMsg ?: (e.message ?: "unknown error"), e)
        }
    }

    private fun ticketCallback(result: SsoLoginResult, service: String): String {
        val response = result.response as? HttpResponse
        val location = response?.location()
        val callback = when {
            !location.isNullOrBlank() -> PythonUrlEncoding.urlJoin(result.finalUrl, location)
            !result.ticket.isNullOrBlank() -> service + (if ("?" in service) "&" else "?") + "ticket=${result.ticket}"
            else -> result.finalUrl
        }
        val callbackTicket = uriQuery(callback)?.split("&")
            ?.firstOrNull { it.startsWith("ticket=") }?.substringAfter("=")
            ?.let { PythonUrlEncoding.unquote(it) }
        if ((callbackTicket.isNullOrBlank()) && result.ticket.isNullOrBlank()) {
            throw LoginError("CAS did not issue a service ticket")
        }
        return callback
    }

    companion object {
        const val SSO_BASE = "https://sso.bit.edu.cn"
    }
}

internal val HttpResponse.isOk get() = status == 200
