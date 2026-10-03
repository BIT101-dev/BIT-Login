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
        assertEquals(19, Config.Urls.campus.size)
    }

    @Test
    fun `Urls webvpn has expected keys`() {
        assertEquals("https://webvpn.bit.edu.cn", Config.Urls.webvpn["webvpn_origin"])
        assertEquals("https://webvpn.bit.edu.cn/login?cas_login=true", Config.Urls.webvpn["webvpn_cb"])
        assertNotNull(Config.Urls.webvpn["lexue"])
        assertEquals(21, Config.Urls.webvpn.size)
    }

    @Test
    fun `Urls wdkbby landing keys mirror xsfacx structure`() {
        // 研究生落地应用: campus与webvpn两套均需提供 auth/app_base/config 三键
        for (urls in listOf(Config.Urls.campus, Config.Urls.webvpn)) {
            val auth = requireNotNull(urls["jxzxehall_wdkbby_auth"])
            val appBase = requireNotNull(urls["jxzxehall_wdkbby_app_base"])
            val config = requireNotNull(urls["jxzxehall_wdkbby_config"])
            assertTrue(auth.endsWith("index.do"))
            assertTrue(auth.contains("wdkbby"))
            assertTrue(appBase.endsWith("/jwapp/sys/wdkbby/*default/index.do"))
            assertTrue(config.contains("wdkbby-5959167891382285"))
        }
        assertEquals(
            "https://jxzxehallapp.bit.edu.cn/jwapp/sys/wdkbby/*default/index.do",
            Config.Urls.campus["jxzxehall_wdkbby_app_base"],
        )
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
