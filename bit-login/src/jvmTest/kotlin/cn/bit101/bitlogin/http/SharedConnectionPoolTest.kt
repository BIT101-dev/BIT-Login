package cn.bit101.bitlogin.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression test for the process-wide shared OkHttp connection pool.
 *
 * All Ktor engines created by [cn.bit101.bitlogin.util.createPlatformHttpClient]
 * share one okhttp3.ConnectionPool. Closing one HttpClient must not break
 * other existing or future clients — Ktor's engine close calls
 * connectionPool.evictAll() (harmless) and shuts down only the per-engine
 * dispatcher, never shared state.
 */
class SharedConnectionPoolTest {

    @Test
    fun `closing one client does not break a later client`() = runTest {
        val server = EchoServer()
        try {
            server.start()
            HttpClient().use { client ->
                assertEquals(200, client.get(server.origin + "/ping").status)
            }
            HttpClient().use { client ->
                val response = client.get(server.origin + "/ping")
                assertEquals(200, response.status)
                assertEquals("pong", response.bodyText)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `two concurrent clients keep separate cookies over the shared pool`() = runTest {
        val server = EchoServer()
        try {
            server.start()
            val a = HttpClient()
            val b = HttpClient()
            try {
                a.addCookie("token", "a-value", "127.0.0.1")
                b.addCookie("token", "b-value", "127.0.0.1")
                assertEquals("token=a-value", a.get(server.origin + "/cookies").bodyText)
                assertEquals("token=b-value", b.get(server.origin + "/cookies").bodyText)
            } finally {
                a.close()
                b.close()
            }
        } finally {
            server.stop()
        }
    }

    private class EchoServer : HttpHandler {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin: String get() = "http://127.0.0.1:${server.address.port}"

        fun start() {
            server.createContext("/", this)
            server.start()
        }

        fun stop() = server.stop(0)

        override fun handle(exchange: HttpExchange) {
            val body = when (exchange.requestURI.path) {
                "/ping" -> "pong"
                "/cookies" -> exchange.requestHeaders.getFirst("Cookie").orEmpty()
                else -> null
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
    }
}
