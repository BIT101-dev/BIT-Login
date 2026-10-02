package cn.bit101.bitlogin.server.util

/**
 * Best-effort scrubbing of personal/sensitive data from error messages before
 * they are persisted, logged, or returned to a caller.
 *
 * Error text is the one place where upstream URLs and user-supplied values
 * (phone numbers, captcha codes, SMS codes, tokens) can accidentally become
 * long-lived: it is stored in `auth_challenges.error`, echoed through the API,
 * and written to logs. These helpers only affect diagnostics; they never change
 * what is sent to (or received from) SSO.
 */
private val URL_PATTERN = Regex("""(?i)\bhttps?://[^\s"'<>()\[\]{}]+""")

/** BIT SSO carries phone/captcha values as path segments of these endpoints. */
private val SENSITIVE_PATH = Regex(
    """(?i)/(?:sendCheckCaptcha|sendSmsCode|findCaptchaCount|generate)/[^\s"'<>()\[\]{}]*"""
)

/** `key=value` / `key: value` assignments for sensitive field names. */
private val KEY_VALUE_PATTERN = Regex(
    """(?i)(\b(?:access[_-]?token|response[_-]?token|token|ticket|cas|sms[_-]?code|captcha[_-]?code|phone|mobile|tel|password|passwd)\b["']?\s*[=:]\s*["']?)([^\s&,;\]})"']+)"""
)

private val BEARER_PATTERN = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+""")

/** Mainland-China mobile numbers, optionally prefixed with +86 / 86. */
private val PHONE_PATTERN = Regex("""(?<!\d)(?:\+?86[-\s]?)?1[3-9]\d{9}(?!\d)""")

/**
 * Removes personal data that can appear even in otherwise human-readable
 * messages: URLs (which may embed identifiers in their path), SSO endpoints
 * that carry a phone/captcha, bearer tokens, and raw mobile numbers.
 *
 * Deliberately leaves diagnostic flags such as `ticket=yes`, `risk=...` or
 * `captcha=yes` untouched so existing clients can keep reading them.
 */
internal fun redactPii(value: String): String {
    var result = URL_PATTERN.replace(value, "[redacted-url]")
    result = SENSITIVE_PATH.replace(result, "/[redacted-path]")
    result = BEARER_PATTERN.replace(result, "$1[redacted]")
    result = PHONE_PATTERN.replace(result, "[redacted-phone]")
    return result
}

/**
 * [redactPii] plus generic `key=value` secret scrubbing. Used where the text
 * is persisted or written to logs, and where diagnostic flags matter less.
 */
internal fun redactSensitive(value: String): String =
    redactPii(KEY_VALUE_PATTERN.replace(value, "$1[redacted]"))
