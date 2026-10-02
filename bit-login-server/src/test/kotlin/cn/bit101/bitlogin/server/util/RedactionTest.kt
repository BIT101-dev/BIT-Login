package cn.bit101.bitlogin.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedactionTest {
    @Test
    fun `redacts urls and raw phone numbers`() {
        val input = "HTTP 500 from https://sso.bit.edu.cn/gate/sso-extend/protected/api/aggregate/sms/publicNoToken/sendCheckCaptcha/DEFAULT/a7B2/13800138000/0008"
        val output = redactPii(input)
        assertFalse(output.contains("13800138000"), output)
        assertFalse(output.contains("a7B2"), output)
        assertTrue(output.contains("[redacted-url]"), output)
    }

    @Test
    fun `redacts bare phones and sso endpoint paths`() {
        val output = redactPii("failed for 13800138000 at /sso-extend/protected/api/aggregate/sms/sendSmsCode/0008")
        assertFalse(output.contains("13800138000"), output)
        assertTrue(output.contains("[redacted-phone]"), output)
        assertTrue(output.contains("/[redacted-path]"), output)
    }

    @Test
    fun `redactPii keeps diagnostic flags intact`() {
        val input = "rejected [status=401, redirects=0, risk=ustc-token, ticket=yes, flow=replaced, captcha=yes]"
        assertEquals(input, redactPii(input))
    }

    @Test
    fun `redactSensitive scrubs key value secrets`() {
        val output = redactSensitive("password=secret123 ticket=ST-abc phone=13800138000 sms_code=1234")
        assertFalse(output.contains("secret123"), output)
        assertFalse(output.contains("ST-abc"), output)
        assertFalse(output.contains("13800138000"), output)
        assertFalse(output.contains("1234"), output)
    }

    @Test
    fun `redactSensitive scrubs url embedded identifiers`() {
        val output = redactSensitive("Login failed: https://service.test/callback?ticket=ST-PRIMARY-SMS")
        assertFalse(output.contains("ST-PRIMARY-SMS"), output)
        assertTrue(output.contains("[redacted-url]"), output)
    }
}
