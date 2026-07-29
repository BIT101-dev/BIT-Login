package cn.bit101.bitlogin.server.perf

import java.nio.file.Path
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import cn.bit101.bitlogin.server.auth.ChallengeHandle
import cn.bit101.bitlogin.server.auth.ChallengeStore
import cn.bit101.bitlogin.server.config.AppConfig
import cn.bit101.bitlogin.server.mainModule

@Tag("perf")
class AuthRoutePollingPerfTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `concurrent status polling returns well-formed snapshots without 500s`() = testApplication {
        val dbPath = tempDir.resolve("perf-route.db").toString()
        // Seed authenticated challenges via a direct store on the same DB file;
        // WAL multi-connection semantics let the app's own store see them,
        // avoiding AuthWorker's live-network login entirely.
        val seedStore = ChallengeStore(database = dbPath, pendingTtl = 300, readyTtl = 1800)
        val handles: List<ChallengeHandle> = (1..32).map {
            val handle = seedStore.create(listOf("jwb"), "route-$it")
            seedStore.complete(handle.challengeId)
            handle
        }

        application { mainModule(AppConfig.fromEnv().copy(authDbPath = dbPath)) }

        // Warm up the pipeline (plugins, store init, JIT) so the first burst's
        // initialization cost doesn't skew the measured percentiles.
        runLoad("route warmup", concurrency = 16, opsPerWorker = 2) { worker, op ->
            val handle = handles[(worker + op) % handles.size]
            client.get("/api/auth/${handle.challengeId}") {
                header("X-Challenge-Token", handle.accessToken)
            }
        }

        val pollReport = runLoad("route polling C=64", concurrency = 64, opsPerWorker = 8) { worker, op ->
            val handle = handles[(worker + op) % handles.size]
            val response = client.get("/api/auth/${handle.challengeId}") {
                header("X-Challenge-Token", handle.accessToken)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            for (field in listOf("challenge_id", "status", "requested_services", "ready_services", "expires_in")) {
                assertTrue(field in body, "snapshot missing field $field: $body")
            }
            assertEquals("authenticated", body["status"]!!.toString().trim('"'))
        }
        println(pollReport)

        val rejectReport = runLoad("route invalid start C=32", concurrency = 32, opsPerWorker = 4) { _, _ ->
            val response = client.post("/api/auth/start") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"u","password":"p","services":["bogus"],"wait_seconds":0.0}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }
        println(rejectReport)
    }
}
