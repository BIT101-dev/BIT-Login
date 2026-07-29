package cn.bit101.bitlogin.server.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import cn.bit101.bitlogin.server.config.AppConfig
import cn.bit101.bitlogin.server.mainModule

class IcsRoutesTest {

    private fun config() = AppConfig.fromEnv()

    @Test
    fun `missing ics file returns 404 not 500`() = testApplication {
        application { mainModule(config()) }
        val response = client.get("/tmp/nonexistent.ics")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["detail"]!!.jsonPrimitive.content.contains("File not found"))
    }

    @Test
    fun `non-ics extension returns 403`() = testApplication {
        application { mainModule(config()) }
        val response = client.get("/tmp/evil.txt")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
