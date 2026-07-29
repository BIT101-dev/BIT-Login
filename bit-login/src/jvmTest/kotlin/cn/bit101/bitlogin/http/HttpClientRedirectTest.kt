package cn.bit101.bitlogin.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for HttpClient's redirect handling.
 *
 * These exist because Ktor's HttpClientConfig.followRedirects defaults to true
 * and Ktor auto-installs HttpRedirect when that flag is true — independent of
 * any explicit `install(HttpRedirect)` call. The previous HttpClient code only
 * gated the explicit install on `follow`, which left noFollowClient silently
 * following redirects, breaking every allowRedirects=false caller.
 */
class HttpClientRedirectTest {

    @Test
    fun `allowRedirects=false returns 302 without following Location`() = runTest {
        val server = RedirectServer()
        try {
            server.start()
            HttpClient().use { client ->
                val response = client.get(server.origin + "/start", allowRedirects = false)
                assertEquals(302, response.status)
                assertEquals("/target", response.location())
                // Final URL must remain the original request URL, not the redirect target.
                assertTrue(response.url.endsWith("/start"), "url was ${response.url}")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `default allowRedirects=true follows the Location chain`() = runTest {
        val server = RedirectServer()
        try {
            server.start()
            HttpClient().use { client ->
                val response = client.get(server.origin + "/start")
                assertEquals(200, response.status)
                assertTrue(response.url.endsWith("/target"), "url was ${response.url}")
                assertEquals("target-body", response.bodyText)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `requestBytes preserves POST method and JSON body`() = runTest {
        val server = RedirectServer()
        try {
            server.start()
            HttpClient().use { client ->
                val response = client.requestBytes(
                    method = HttpMethod.Post,
                    url = server.origin + "/binary",
                    json = JsonObject(mapOf("phone" to JsonPrimitive("13800138000"))),
                )
                assertEquals(byteArrayOf(0, 1, 2, 0xff.toByte()).toList(), response.toList())
                assertEquals("POST", server.lastMethod)
                assertEquals("{\"phone\":\"13800138000\"}", server.lastBody)
                assertTrue(server.lastContentType.startsWith("application/json"))
            }
        } finally {
            server.stop()
        }
    }

    /** Minimal local HTTP server that emits a 302 then a 200 for the target path. */
    private class RedirectServer : HttpHandler {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        val origin: String get() = "http://127.0.0.1:$port"
        @Volatile var lastMethod: String = ""
        @Volatile var lastBody: String = ""
        @Volatile var lastContentType: String = ""

        fun start() {
            server.createContext("/", this)
            server.start()
        }

        fun stop() = server.stop(0)

        override fun handle(exchange: HttpExchange) {
            when (exchange.requestURI.path) {
                "/start" -> {
                    exchange.responseHeaders.add("Location", "/target")
                    exchange.sendResponseHeaders(302, -1)
                }
                "/target" -> {
                    val body = "target-body".toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                "/binary" -> {
                    lastMethod = exchange.requestMethod
                    lastBody = exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
                    lastContentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                    val body = byteArrayOf(0, 1, 2, 0xff.toByte())
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
    }
}
