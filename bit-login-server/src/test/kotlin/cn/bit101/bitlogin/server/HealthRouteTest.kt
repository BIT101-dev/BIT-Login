package cn.bit101.bitlogin.server

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

class HealthRouteTest {

    @Test
    fun `GET slash returns running message`() = testApplication {
        application { mainModule(AppConfig.fromEnv()) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["message"]?.jsonPrimitive?.content?.contains("running") == true)
    }
}
