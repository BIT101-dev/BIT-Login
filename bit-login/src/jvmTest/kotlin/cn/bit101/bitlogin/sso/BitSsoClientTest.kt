package cn.bit101.bitlogin.sso

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import cn.bit101.bitlogin.http.HttpResponse

class BitSsoClientTest {
    private val key = "MDEyMzQ1Njc4OWFiY2RlZg=="

    @Test
    fun `password login returns ticket from redirect`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(302, "", "https://service.test/callback?ticket=ST-1"),
        )
        val result = BitSsoClient("https://sso.test", transport).loginPassword("student", "password", "https://service.test/callback", followRedirects = false)
        assertEquals("ST-1", result.ticket)
        assertEquals("UsernamePassword", transport.requests[2].form?.get("type"))
        assertEquals("https://sso.test", transport.requests[2].headers["Origin"])
    }

    @Test
    fun `captcha requirement fails without OCR solver`() = runTest {
        val transport = ScriptedTransport(response(200, loginHtml()), response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":true}}"))
        assertThrows<CaptchaError> {
            BitSsoClient("https://sso.test", transport).loginPassword("student", "password")
        }
    }

    @Test
    fun `captcha demand with solver proceeds and sends encrypted payload`() = runTest {
        val captchaPayload = """{"ts":"abc"}"""
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":true,\"captchaUrl\":\"data:image/png;base64,iVBORw0KGgo=\",\"captchaPayload\":$captchaPayload}}"),
            response(302, "", "https://service.test/callback?ticket=ST-CAP"),
        )
        transport.imageBytes = byteArrayOf(1, 2, 3)
        val solver: CaptchaSolver = { _, _ -> "42" }
        val result = BitSsoClient("https://sso.test", transport, captchaSolver = solver)
            .loginPassword("student", "password", "https://service.test/callback", followRedirects = false)
        assertEquals("ST-CAP", result.ticket)
        assertEquals("42", transport.requests[2].form?.get("captcha_code"))
        // captcha_payload should not be the empty "{}" — it should encrypt the real payload.
        assertFalse(transport.requests[2].form?.get("captcha_payload") == Crypto.encryptAesBase64("{}", key))
    }

    @Test
    fun `second factor flow submits callback SMS code with trustDevice false`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(200, secondFactorHtml()),
            response(200, "{\"code\":200,\"data\":{\"tel\":\"opaque\",\"maskTel\":\"138****8000\"}}"),
            response(200, "{\"code\":200}"),
            response(200, "{\"code\":200}"),
            response(302, "", "https://service.test/callback?ticket=ST-SMS"),
        )
        val result = BitSsoClient("https://sso.test", transport).loginPassword("student", "password", smsCodeCallback = { "123456" }, trustDevice = false, followRedirects = false)
        assertEquals("ST-SMS", result.ticket)
        assertEquals("123456", transport.requests.last().form?.get("password"))
        assertEquals("false", transport.requests.last().form?.get("trustDevice"))
    }

    @Test
    fun `second factor checkToken JSON includes trustDevice`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(200, secondFactorHtml()),
            response(200, "{\"code\":200,\"data\":{\"tel\":\"opaque\",\"maskTel\":\"138****8000\"}}"),
            response(200, "{\"code\":200}"),
            response(200, "{\"code\":200}"),
            response(302, "", "https://service.test/callback?ticket=ST-SMS"),
        )
        BitSsoClient("https://sso.test", transport).loginPassword("student", "password", smsCodeCallback = { "123456" }, trustDevice = true, followRedirects = false)
        // The 5th request (index 5) is the checkToken JSON POST.
        val checkTokenJson = transport.requests[5].json
        assertNotNull(checkTokenJson)
        assertEquals(true, checkTokenJson!!["trustDevice"]?.toString()?.toBoolean())
    }

    @Test
    fun `SMS send tolerates 'still valid' response`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(200, secondFactorHtml()),
            response(200, "{\"code\":200,\"data\":{\"tel\":\"opaque\",\"maskTel\":\"138****8000\"}}"),
            response(200, "{\"code\":400,\"message\":\"验证码在有效期内，请勿重复发送\"}"),
            response(200, "{\"code\":200}"),
            response(302, "", "https://service.test/callback?ticket=ST-SMS"),
        )
        val result = BitSsoClient("https://sso.test", transport).loginPassword("student", "password", smsCodeCallback = { "123456" }, followRedirects = false)
        assertEquals("ST-SMS", result.ticket)
    }

    @Test
    fun `loginResult returns cookies from transport`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(302, "", "https://service.test/callback?ticket=ST-1"),
        )
        transport.cookies["TGC"] = "ticket-granting-cookie"
        val result = BitSsoClient("https://sso.test", transport).loginPassword("student", "password", "https://service.test/callback", followRedirects = false)
        assertEquals("ticket-granting-cookie", result.cookies["TGC"])
    }

    @Test
    fun `loginResult risk mode propagates to LoginError`() = runTest {
        // Login page returned again → login rejected.
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(200, loginHtml()),
        )
        val ex = assertThrows<LoginError> {
            BitSsoClient("https://sso.test", transport).loginPassword("student", "wrong", followRedirects = false)
        }
        assertEquals("not-required", ex.riskMode)
        assertTrue(ex.message!!.contains("risk=not-required"))
    }

    @Test
    fun `page null without ticket throws ConfigurationError`() = runTest {
        val transport = ScriptedTransport(
            response(200, "<html>no form here</html>"),
        )
        assertThrows<ConfigurationError> {
            BitSsoClient("https://sso.test", transport).loginPassword("student", "password")
        }
    }

    @Test
    fun `GET login page returning 4xx raises SsoHttpException for Python raise_for_status parity`() = runTest {
        // Python wraps `response.raise_for_status()` failures as login_error
        // ("统一身份认证请求失败（HTTP N）"). The Kotlin port should surface the
        // same status code via SsoHttpException so SsoLogin can localize it.
        val transport = ScriptedTransport(response(404, ""))
        val error = assertThrows<SsoHttpException> {
            BitSsoClient("https://sso.test", transport).loginPassword("student", "password")
        }
        assertEquals(404, error.statusCode)
    }

    @Test
    fun `POST login rejection does NOT raise SsoHttpException — loginResult inspects body`() = runTest {
        // 400/401/403 on /cas/login must NOT bypass loginResult: that's where
        // the human-readable rejection message ("用户名或密码错误") is built.
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(403, loginHtml()),
        )
        val error = assertThrows<LoginError> {
            BitSsoClient("https://sso.test", transport).loginPassword("student", "wrong", followRedirects = false)
        }
        assertEquals(403, error.statusCode)
    }

    @Test
    fun `POST login server error raises SsoHttpException`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            response(200, "{\"code\":200,\"data\":{\"captchaInvisible\":false}}"),
            response(500, "upstream failure"),
        )
        val error = assertThrows<SsoHttpException> {
            BitSsoClient("https://sso.test", transport).loginPassword("student", "password", followRedirects = false)
        }
        assertEquals(500, error.statusCode)
    }

    @Test
    fun `primary SMS generation preserves POST JSON request`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            HttpResponse(200, emptyMap(), "", "https://sso.test/generate", byteArrayOf(1, 2, 3)),
            response(200, "{\"code\":500,\"message\":\"failed\"}"),
        )
        assertThrows<SmsVerificationError> {
            BitSsoClient("https://sso.test", transport, captchaSolver = { _, _ -> "42" })
                .loginSms("13800138000", { "123456" })
        }
        val generate = transport.requests[1]
        assertEquals(HttpMethod.Post, generate.method)
        assertEquals("13800138000", generate.json?.get("phone")?.jsonPrimitive?.content)
        assertEquals("DEFAULT", generate.json?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `login_sms without solver throws CaptchaError`() = runTest {
        val transport = ScriptedTransport(
            response(200, loginHtml()),
            HttpResponse(200, emptyMap(), "", "https://sso.test/generate", byteArrayOf(1, 2, 3)),
        )
        assertThrows<CaptchaError> {
            BitSsoClient("https://sso.test", transport).loginSms("13800138000", { "123456" })
        }
    }

    @Test
    fun `login_sms rejects invalid phone`() = runTest {
        val transport = ScriptedTransport(response(200, loginHtml()))
        assertThrows<ConfigurationError> {
            BitSsoClient("https://sso.test", transport).loginSms("123", { "123456" })
        }
    }

    @Test
    fun `browser default headers are available`() {
        assertEquals("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            BitSsoClient.BROWSER_DEFAULT_HEADERS["User-Agent"])
        assertNotNull(BitSsoClient.BROWSER_DEFAULT_HEADERS["sec-ch-ua"])
        assertEquals("?0", BitSsoClient.BROWSER_DEFAULT_HEADERS["sec-ch-ua-mobile"])
    }

    private fun loginHtml() = "<form action=\"login\"><span id=\"login-page-flowkey\">flow</span><span id=\"login-croypto\">$key</span></form>"
    private fun secondFactorHtml() = "<form id=\"secondSmsLoginForm\" action=\"login\"><span id=\"login-page-flowkey\">second</span><span id=\"user-object-id\">user</span></form>"
    private fun response(status: Int, body: String, url: String = "https://sso.test/cas/login") = HttpResponse(status, emptyMap(), body, url)

    private class ScriptedTransport(vararg responses: HttpResponse) : SsoTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<SsoRequest>()
        val cookies = mutableMapOf<String, String>()
        var imageBytes: ByteArray = ByteArray(0)
        override suspend fun request(request: SsoRequest): HttpResponse {
            requests += request
            return responses.removeFirst()
        }
        override suspend fun requestBytes(request: SsoRequest): ByteArray {
            requests += request
            // Pop the next response if available, else return imageBytes for binary fetches.
            if (responses.isNotEmpty()) responses.removeFirst()
            return imageBytes
        }
        override fun cookieValue(name: String): String = cookies[name].orEmpty()
        override fun cookieMap(): Map<String, String> = cookies.toMap()
    }
}
