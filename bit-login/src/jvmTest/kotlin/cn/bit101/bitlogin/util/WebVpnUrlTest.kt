package cn.bit101.bitlogin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebVpnUrlTest {

    /** Golden vectors produced by Python `bit_login.utils.encode_vpn_host`. */
    private val vpnVectors: List<Pair<String, String>> = listOf(
        "jwms.bit.edu.cn" to "77726476706e69737468656265737421fae04c8f69326144300d8db9d6562d",
        "sso.bit.edu.cn"  to "77726476706e69737468656265737421e3e44ed225397c1e7b0c9ce29b5b",
        "ibit.yanhekt.cn" to "77726476706e69737468656265737421f9f548886929695e760d82b8d6562d",
        "a"               to "77726476706e69737468656265737421f1",
        "bit.edu.cn"      to "77726476706e69737468656265737421f2fe55d222347d1e7d06",
        "example.com"     to "77726476706e69737468656265737421f5ef4091373c6d1e7d0784",
    )

    @Test
    fun `encodeVpnHost matches Python golden vectors`() {
        vpnVectors.forEach { (host, expected) ->
            assertEquals(expected, WebVpnUrl.encodeVpnHost(host), "host=$host")
        }
    }

    @Test
    fun `encodeVpnHost output length is 32 iv-hex + 2*host chars`() {
        vpnVectors.forEach { (host, encoded) ->
            assertEquals(32 + host.length * 2, encoded.length, "host=$host")
        }
    }

    @Test
    fun `encodeVpnHost is deterministic`() {
        vpnVectors.forEach { (host, _) ->
            assertEquals(WebVpnUrl.encodeVpnHost(host), WebVpnUrl.encodeVpnHost(host))
        }
    }

    @Test
    fun `encodeVpnHost is idempotent under iv prefix`() {
        // The 32-char IV prefix is the hex of "wrdvpnisthebest!" and is constant.
        vpnVectors.forEach { (_, encoded) ->
            assertEquals("77726476706e69737468656265737421", encoded.substring(0, 32))
        }
    }

    @Test
    fun `convertToWebvpnUrl matches Python golden vectors`() {
        assertEquals(
            "https://webvpn.bit.edu.cn/http/77726476706e69737468656265737421fae043d225397c1e7b0c9ce29b5b/",
            WebVpnUrl.convertToWebvpnUrl("http://jwb.bit.edu.cn/"),
        )
        assertEquals(
            "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3e44ed225397c1e7b0c9ce29b5b/cas/login",
            WebVpnUrl.convertToWebvpnUrl("https://sso.bit.edu.cn/cas/login"),
        )
        assertEquals(
            "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e7f2438a373e2652771cc7a99c406d36da/login?cas=true",
            WebVpnUrl.convertToWebvpnUrl("https://webvpn.bit.edu.cn/login?cas=true"),
        )
    }

    @Test
    fun `convertToWebvpnUrl returns input as-is when not a URL`() {
        val raw = "notaurl"
        assertEquals(raw, WebVpnUrl.convertToWebvpnUrl(raw))
    }

    @Test
    fun `convertToWebvpnUrl preserves query and fragment`() {
        val out = WebVpnUrl.convertToWebvpnUrl("https://sso.bit.edu.cn/cas/login?service=foo#bar")
        assertTrue(out.contains("?service=foo"))
        assertTrue(out.contains("#bar"))
        assertNotEquals("https://sso.bit.edu.cn/cas/login?service=foo#bar", out)
    }
}
