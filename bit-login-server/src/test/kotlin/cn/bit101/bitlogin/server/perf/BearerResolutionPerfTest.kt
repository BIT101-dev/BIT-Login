package cn.bit101.bitlogin.server.perf

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.server.auth.ChallengeHandle
import cn.bit101.bitlogin.server.auth.ChallengeStore

@Tag("perf")
class BearerResolutionPerfTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `bearer resolution plus upstream call reuses shared connection pool`() = runBlocking {
        val upstream = FakeUpstream()
        upstream.start()
        try {
            val store = ChallengeStore(
                database = tempDir.resolve("perf.db").toString(),
                pendingTtl = 300,
                readyTtl = 1800,
                pollIntervalMs = 250,
            )
            val seed = HttpClient()
            seed.addCookie("sid", "session-cookie", "127.0.0.1")
            val handles: List<ChallengeHandle> = (1..32).map {
                val handle = store.create(listOf("jwb"), "bearer-$it")
                store.storeService(handle.challengeId, "jwb", seed, buildJsonObject {})
                store.complete(handle.challengeId)
                handle
            }
            seed.close()

            var totalRequests = 0
            for (concurrency in listOf(1, 16, 64)) {
                val opsPerWorker = when {
                    concurrency <= 1 -> 100
                    concurrency <= 16 -> 30
                    else -> 8
                }
                totalRequests += concurrency * opsPerWorker
                val report = runLoad("bearer C=$concurrency", concurrency, opsPerWorker) { worker, op ->
                    val handle = handles[(worker + op) % handles.size]
                    val client = store.getSession(handle.challengeId, handle.accessToken, "jwb")
                    // Production (AuthServiceExecutor) never closes restored clients —
                    // Ktor's engine close would evictAll() the *shared* pool, killing
                    // keep-alive reuse. Wrappers are GC'd; idle connections stay pooled.
                    val response = client.get(upstream.origin + "/data")
                    assertEquals(200, response.status)
                    assertTrue(response.bodyText.contains("sid=session-cookie"), "missing restored cookie: ${response.bodyText}")
                }
                println(report)
            }

            val connections = upstream.connectionCount()
            println("upstream: $totalRequests requests over $connections TCP connections")
            assertTrue(
                connections < totalRequests / 4,
                "expected connection reuse, got $connections connections for $totalRequests requests",
            )
        } finally {
            upstream.stop()
        }
        Unit
    }

    /** Returns a tiny JSON body echoing the Cookie header; tracks distinct TCP peers. */
    private class FakeUpstream : HttpHandler {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val peers = ConcurrentHashMap.newKeySet<String>()
        val origin: String get() = "http://127.0.0.1:${server.address.port}"

        fun start() {
            server.createContext("/", this)
            server.start()
        }

        fun stop() = server.stop(0)

        fun connectionCount(): Int = peers.size

        override fun handle(exchange: HttpExchange) {
            peers.add("${exchange.remoteAddress.address.hostAddress}:${exchange.remoteAddress.port}")
            val body = """{"ok":true,"cookies":"${exchange.requestHeaders.getFirst("Cookie").orEmpty()}"}"""
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
    }
}
