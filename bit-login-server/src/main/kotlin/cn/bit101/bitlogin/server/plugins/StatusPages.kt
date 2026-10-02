package cn.bit101.bitlogin.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import cn.bit101.bitlogin.login.LoginError
import cn.bit101.bitlogin.server.util.redactPii
import cn.bit101.bitlogin.server.util.redactSensitive

private val log = LoggerFactory.getLogger("bit-login-server.StatusPages")

class HttpException(val status: Int, message: String, val jsonBody: JsonElement? = null) : RuntimeException(message)

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<LoginError> { call, cause ->
            val message = redactPii(cause.message ?: "Login failed")
            log.warn("Login failed: $message")
            call.respond(HttpStatusCode.Unauthorized, errorBody("Login failed: $message"))
        }
        exception<HttpException> { call, cause ->
            val code = HttpStatusCode.fromValue(cause.status)
            val body = if (cause.jsonBody != null) {
                JsonObject(mapOf("detail" to cause.jsonBody))
            } else {
                errorBody(cause.message ?: "Error")
            }
            call.respond(code, body)
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, errorBody(cause.message ?: "Invalid request"))
        }
        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, errorBody(cause.message ?: "Malformed JSON"))
        }
        exception<Throwable> { call, cause ->
            val detail = redactSensitive(cause.message ?: "Internal server error")
            log.error("Unhandled error: {}", redactSensitive(cause.stackTraceToString()))
            call.respond(HttpStatusCode.InternalServerError, errorBody(detail))
        }
    }
}

private fun errorBody(detail: String) = JsonObject(mapOf("detail" to JsonPrimitive(detail)))
