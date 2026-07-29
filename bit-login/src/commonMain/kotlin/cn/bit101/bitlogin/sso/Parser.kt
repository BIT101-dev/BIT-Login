package cn.bit101.bitlogin.sso

/**
 * CAS login/second-factor HTML parser. The Jsoup-backed implementation lives in
 * the shared JVM source set; only the signatures live in common.
 */
data class ParsedLoginPage(
    val page: LoginPage?,
    val errorMessage: String,
    val errorCode: String,
)

expect object SsoParser {
    fun parseLoginPage(html: String, responseUrl: String): ParsedLoginPage

    fun parseSecondFactorPage(html: String, responseUrl: String): SecondFactorPage?
}
