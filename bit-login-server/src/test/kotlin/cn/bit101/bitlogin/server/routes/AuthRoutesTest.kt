package cn.bit101.bitlogin.server.routes

import io.ktor.client.request.delete
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import cn.bit101.bitlogin.server.config.AppConfig
import cn.bit101.bitlogin.server.mainModule

class AuthRoutesTest {
    @TempDir lateinit var tempDir: java.nio.file.Path

    private fun config() = AppConfig.fromEnv().copy(
        authDbPath = tempDir.resolve("auth-test.db").toString()
    )

    @Test
    fun `start with invalid service returns 422`() = testApplication {
        application { mainModule(config()) }
        val response = client.post("/api/auth/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"u","password":"p","services":["bogus"],"wait_seconds":0.0}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        // Body must be a nested JSON object, not a stringified JSON string.
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val detail = body["detail"]!!.jsonObject
        assertEquals("invalid authentication services", detail["message"]!!.jsonPrimitive.content)
        assertTrue(detail["invalid"]!!.jsonArray.isNotEmpty(), "invalid array should contain 'bogus'")
        assertTrue(detail["supported"]!!.jsonArray.isNotEmpty(), "supported array should list valid services")
    }

    @Test
    fun `start with out-of-range wait_seconds returns 422`() = testApplication {
        application { mainModule(config()) }
        val response = client.post("/api/auth/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"u","password":"p","services":["jwb"],"wait_seconds":10.0}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `status with unknown challenge returns 404`() = testApplication {
        application { mainModule(config()) }
        val response = client.get("/api/auth/nonexistent") {
            header("X-Challenge-Token", "any-token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `start and status lifecycle`() = testApplication {
        application { mainModule(config()) }
        val startResp = client.post("/api/auth/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"u","password":"p","services":["jwb"],"wait_seconds":0.0}""")
        }
        assertEquals(HttpStatusCode.Accepted, startResp.status)
        val body = Json.parseToJsonElement(startResp.bodyAsText()).jsonObject
        val challengeId = body["challenge_id"]!!.jsonPrimitive.content
        val token = body["access_token"]!!.jsonPrimitive.content

        val statusResp = client.get("/api/auth/$challengeId") {
            header("X-Challenge-Token", token)
        }
        assertEquals(HttpStatusCode.OK, statusResp.status)
    }

    @Test
    fun `status with wrong token returns 403`() = testApplication {
        application { mainModule(config()) }
        val startResp = client.post("/api/auth/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"u","password":"p","services":["jwb"],"wait_seconds":0.0}""")
        }
        val body = Json.parseToJsonElement(startResp.bodyAsText()).jsonObject
        val challengeId = body["challenge_id"]!!.jsonPrimitive.content

        val resp = client.get("/api/auth/$challengeId") {
            header("X-Challenge-Token", "wrong-token")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `delete returns deleted status`() = testApplication {
        application { mainModule(config()) }
        val startResp = client.post("/api/auth/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"u","password":"p","services":["jwb"],"wait_seconds":0.0}""")
        }
        val body = Json.parseToJsonElement(startResp.bodyAsText()).jsonObject
        val challengeId = body["challenge_id"]!!.jsonPrimitive.content
        val token = body["access_token"]!!.jsonPrimitive.content

        val delResp = client.delete("/api/auth/$challengeId") {
            header("X-Challenge-Token", token)
        }
        assertEquals(HttpStatusCode.OK, delResp.status)
        val delBody = Json.parseToJsonElement(delResp.bodyAsText()).jsonObject
        assertEquals("deleted", delBody["status"]!!.jsonPrimitive.content)
    }
}
