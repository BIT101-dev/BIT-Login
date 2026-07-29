package cn.bit101.bitlogin.sso

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FingerprintTest {
    private val profile = BrowserFingerprintProfile()

    @Test
    fun `fingerprint is deterministic`() {
        val a = profile.build("device-cookie", "Mozilla/5.0", "group-1")
        val b = profile.build("device-cookie", "Mozilla/5.0", "group-1")
        assertEquals(a, b)
    }

    @Test
    fun `fingerprint structure matches Python BrowserFingerprintProfile`() {
        val fp = profile.build("dev", "UA")
        assertEquals("dev", fp["cookieValue"])
        assertEquals("UA", fp["userAgent"])
        assertEquals("support", fp["platformAuthenticator"])
        assertEquals("", fp["localgroupId"])
        // timezone, platform, language are raw JSON strings (not hashed)
        assertEquals("\"Asia/Shanghai\"", fp["timezone"])
        assertEquals("\"MacIntel\"", fp["platform"])
        assertEquals("\"zh-CN\"", fp["language"])
        assertEquals("[956,1470]", fp["screenResolution"])
        // fonts, deviceMemory, hardwareConcurrency, cpuClass, fingerprint are SHA-256
        assertEquals(64, fp["fingerprint"]!!.length)
        assertTrue(fp["fingerprint"]!!.all { it in "0123456789abcdef" })
    }

    @Test
    fun `different cookie values are reflected in cookieValue field`() {
        val a = profile.build("cookie-a", "UA")
        val b = profile.build("cookie-b", "UA")
        assertNotEquals(a["cookieValue"], b["cookieValue"])
        // fingerprint hash is browser characteristics only, not cookie/UA
        assertEquals(a["fingerprint"], b["fingerprint"])
    }

    @Test
    fun `different browser profiles produce different fingerprints`() {
        val a = BrowserFingerprintProfile().build("c", "ua")
        val b = BrowserFingerprintProfile(fonts = listOf("Comic Sans")).build("c", "ua")
        assertNotEquals(a["fingerprint"], b["fingerprint"])
    }

    @Test
    fun `combined fingerprint hashes all JS-JSON values`() {
        val fp = profile.build("c", "ua", "g")
        val expectedHashes = setOf("fonts", "deviceMemory", "hardwareConcurrency", "cpuClass", "fingerprint")
        expectedHashes.forEach { key ->
            val value = fp[key]
            assertTrue(value!!.length == 64 && value.all { it in "0123456789abcdef" }, "$key should be a SHA-256 hex digest")
        }
    }
}
