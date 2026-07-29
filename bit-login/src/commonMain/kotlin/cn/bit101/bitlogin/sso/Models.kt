package cn.bit101.bitlogin.sso

data class LoginPage(
    val execution: String,
    val cryptoKey: String,
    val formAction: String,
    val recaptchaVendor: String = "",
    val riskSystem: String = "",
    val targetSystem: String = "",
    val siteId: String = "",
)

data class SecondFactorPage(
    val execution: String,
    val formAction: String,
    val userObjectId: String,
    val userId: String = "",
    val phone: String = "",
)

data class CaptchaContext(
    val purpose: String,
    val username: String = "",
    val phone: String = "",
)

data class SmsCodeContext(
    val phone: String,
    val maskedPhone: String,
    val purpose: String = "sms_login",
)

data class RiskContext(
    val loginType: String,
    val username: String,
    val targetSystem: String,
    val siteId: String,
)

data class SsoLoginResult(
    val response: Any?,
    val finalUrl: String,
    val ticket: String?,
    val cookies: Map<String, String>,
)

typealias SmsCodeCallback = suspend (SmsCodeContext) -> String
typealias CaptchaSolver = suspend (ByteArray, CaptchaContext) -> String
typealias RiskTokenProvider = suspend (RiskContext) -> Map<String, Any?>
