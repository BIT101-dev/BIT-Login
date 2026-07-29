package cn.bit101.bitlogin.sso

open class BitSsoError(message: String, cause: Throwable? = null) : Exception(message, cause)

class ConfigurationError(message: String, cause: Throwable? = null) : BitSsoError(message, cause)

class CaptchaError(message: String, cause: Throwable? = null) : BitSsoError(message, cause)

class SmsVerificationError(message: String, cause: Throwable? = null) : BitSsoError(message, cause)

/**
 * Mirrors Python `requests.HTTPError` raised by `response.raise_for_status()`.
 * Carries the HTTP status so SsoLogin can translate it to the same Chinese
 * messages the Python wrapper produces (429 / 5xx / other 4xx).
 */
class SsoHttpException(
    val statusCode: Int,
    val url: String,
    val bodyText: String,
) : BitSsoError("HTTP $statusCode from $url")

class LoginError(
    message: String,
    val code: String = "",
    val finalUrl: String = "",
    val statusCode: Int? = null,
    val redirectCount: Int = 0,
    val ticketIssued: Boolean = false,
    val riskMode: String = "",
    val flowReplaced: Boolean? = null,
    val captchaRequired: Boolean = false,
) : BitSsoError(
    buildString {
        append(message)
        if (code.isNotEmpty()) append(" (code: ").append(code).append(')')
        append(" [")
        statusCode?.let { append("status=").append(it).append(", ") }
        append("redirects=").append(redirectCount)
        append(", risk=").append(riskMode.ifEmpty { "unknown" })
        append(", ticket=").append(if (ticketIssued) "yes" else "no")
        append(", flow=").append(
            when (flowReplaced) {
                true -> "replaced"
                false -> "same"
                null -> "unknown"
            },
        )
        append(", captcha=").append(if (captchaRequired) "yes" else "no")
        append(']')
    },
)

/**
 * Mirrors Python `_LOGIN_ERROR_MESSAGES` in `client.py`. Maps CAS error codes
 * to human-readable Chinese messages.
 */
val LOGIN_ERROR_MESSAGES: Map<String, String> = mapOf(
    "1030027" to "用户名或密码错误",
    "1030028" to "账号已被锁定",
    "1030031" to "用户名或密码错误",
    "1320007" to "验证码错误或已失效",
    "1320010" to "图形验证码错误",
    "1330001" to "登录被账号风控拒绝",
    "1410040" to "账号状态无效",
    "1410041" to "账号状态无效",
    "3910001" to "账号已休眠，请先完成账号激活",
)

/**
 * Mirrors Python `_format_login_error`. Returns (message, code) tuple.
 *
 * - If `message` is empty but looks numeric, swap it into `code`.
 * - If `message` is non-empty, use it.
 * - If `code` is non-empty, look up [LOGIN_ERROR_MESSAGES] (default "CAS login failed").
 * - If `ticketIssued`, return the ticket-bounce message.
 * - If `riskMode == "error-fallback"`, return the USTC risk failure message.
 * - Otherwise return the generic execution-expired message.
 */
fun formatLoginError(
    rawMessage: String,
    rawCode: String,
    ticketIssued: Boolean,
    riskMode: String,
): Pair<String, String> {
    var message = rawMessage.trim()
    var code = rawCode.trim()
    if (code.isEmpty() && message.isNotEmpty() && message.all { it.isDigit() }) {
        code = message
        message = ""
    }
    if (message.isNotEmpty()) return message to code
    if (code.isNotEmpty()) return (LOGIN_ERROR_MESSAGES[code] ?: "CAS login failed") to code
    if (ticketIssued) return "CAS issued a service ticket, but the service redirected back to the login page" to ""
    if (riskMode == "error-fallback") return "CAS returned the login page without an error code after USTC risk-token acquisition failed" to ""
    return "CAS returned the login page without an error code; the execution may have expired or risk control may have rejected the request" to ""
}
