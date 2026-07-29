package cn.bit101.bitlogin.http

import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackingCookieStorageTest {
    @Test
    fun `secure cookie is sent only over HTTPS`() = runTest {
        val storage = TrackingCookieStorage()
        storage.addCookie(
            Url("https://example.test/"),
            Cookie("sid", "secret", domain = "example.test", path = "/", secure = true),
        )
        assertTrue(storage.get(Url("https://example.test/path")).any { it.name == "sid" })
        assertFalse(storage.get(Url("http://example.test/path")).any { it.name == "sid" })
    }

    @Test
    fun `expired cookie is removed using GMTDate millisecond units`() = runTest {
        val storage = TrackingCookieStorage()
        storage.addCookie(
            Url("https://example.test/"),
            Cookie("old", "value", domain = "example.test", expires = GMTDate(System.currentTimeMillis() - 1_000)),
        )
        assertTrue(storage.get(Url("https://example.test/")).isEmpty())
        assertTrue(storage.asMap().isEmpty())
    }

    @Test
    fun `cookie detail expiry round trips as epoch seconds`() = runTest {
        val expiresSeconds = System.currentTimeMillis() / 1000 + 600
        HttpClient().use { client ->
            client.addCookie("sid", "value", "example.test", secure = true, expiresEpochSeconds = expiresSeconds)
            assertEquals(expiresSeconds, client.cookieDetails().single().expires)
        }
    }
}
