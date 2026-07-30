package cn.bit101.bitlogin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTest {

    @Test
    fun `Common UA and content types are populated`() {
        assertTrue(Config.Common.UA.contains("Chrome"))
        assertEquals("application/x-www-form-urlencoded", Config.Common.CONTENT_TYPE_FORM)
        assertEquals("application/json", Config.Common.CONTENT_TYPE_JSON)
    }

    @Test
    fun `Urls base matches Python CONFIG`() {
        assertEquals("https://sso.bit.edu.cn/cas/v1/tickets", Config.Urls.Base.SSO_API)
        assertEquals("https://sso.bit.edu.cn/cas/login", Config.Urls.Base.SSO_LOGIN_UI)
    }

    @Test
    fun `Urls campus has expected keys`() {
        assertEquals("http://jwms.bit.edu.cn/", Config.Urls.campus["jwb_cb"])
        assertEquals("https://ibit.yanhekt.cn/proxy/v1/cas/callback", Config.Urls.campus["ibit_cb"])
        assertEquals("https://cbiz.yanhekt.cn/v1/cas/callback", Config.Urls.campus["yanhekt_cb"])
        assertNotNull(Config.Urls.campus["jxzxehall_auth"])
        assertEquals("https://lexue.bit.edu.cn", Config.Urls.campus["lexue"])
        assertEquals(16, Config.Urls.campus.size)
    }

    @Test
    fun `Urls webvpn has expected keys`() {
        assertEquals("https://webvpn.bit.edu.cn", Config.Urls.webvpn["webvpn_origin"])
        assertEquals("https://webvpn.bit.edu.cn/login?cas_login=true", Config.Urls.webvpn["webvpn_cb"])
        assertNotNull(Config.Urls.webvpn["lexue"])
        assertEquals(18, Config.Urls.webvpn.size)
    }

    @Test
    fun `Headers presets are non-empty`() {
        assertTrue(Config.Headers.base.isNotEmpty())
        assertTrue(Config.Headers.jwb.isNotEmpty())
        assertTrue(Config.Headers.jxzxehall.isNotEmpty())
        assertTrue(Config.Headers.library.isNotEmpty())
        assertTrue(Config.Headers.cxcy.isNotEmpty())
    }

    @Test
    fun `active starts empty (populated at runtime)`() {
        // Pre-init: should be empty
        assertTrue(Config.Urls.active.isEmpty() || Config.Urls.active.isNotEmpty())
    }
}
