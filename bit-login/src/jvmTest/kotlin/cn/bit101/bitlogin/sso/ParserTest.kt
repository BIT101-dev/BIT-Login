package cn.bit101.bitlogin.sso

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ParserTest {
    @Test
    fun `parses login configuration and relative form action`() {
        val parsed = SsoParser.parseLoginPage(
            """
            <form action="submit"><div id="login-page-flowkey">flow</div><div id="login-croypto">a2V5MTIzNDU2Nzg5MDEyMw==</div>
            <span id="recaptchaVendor">vendor</span><span id="riskSystemSwitch">USTC</span>
            <span id="targetSystem">target</span><span id="siteId">site</span></form>
            """.trimIndent(),
            "https://sso.bit.edu.cn/cas/login?service=https://example.test",
        )
        assertEquals("flow", parsed.page?.execution)
        assertEquals("https://sso.bit.edu.cn/cas/submit", parsed.page?.formAction)
        assertEquals("USTC", parsed.page?.riskSystem)
        assertEquals("", parsed.errorMessage)
    }

    @Test
    fun `uses login action and surfaces CAS error when configuration is incomplete`() {
        val parsed = SsoParser.parseLoginPage(
            "<p id=\"login-page-flowkey\">flow</p><p id=\"login-error-msg\">denied</p><p id=\"login-error-code\">1030027</p>",
            "https://sso.bit.edu.cn/cas/login?service=https://example.test",
        )
        assertNull(parsed.page)
        assertEquals("denied", parsed.errorMessage)
        assertEquals("1030027", parsed.errorCode)
    }

    @Test
    fun `parses valid second factor page`() {
        val page = SsoParser.parseSecondFactorPage(
            """
            <form id="secondSmsLoginForm" action="/cas/login"><span id="login-page-flowkey">second-flow</span>
            <span id="user-object-id">object-id</span><span id="second-auth-user-id">user-id</span>
            <span id="phone-number">13800138000</span></form>
            """.trimIndent(),
            "https://sso.bit.edu.cn/cas/login",
        )
        assertEquals("second-flow", page?.execution)
        assertEquals("https://sso.bit.edu.cn/cas/login", page?.formAction)
        assertEquals("13800138000", page?.phone)
    }

    @Test
    fun `does not mistake an ordinary login page for second factor`() {
        assertNull(
            SsoParser.parseSecondFactorPage(
                "<span id=\"login-page-flowkey\">flow</span><span id=\"user-object-id\">object</span>",
                "https://sso.bit.edu.cn/cas/login",
            ),
        )
    }
}
