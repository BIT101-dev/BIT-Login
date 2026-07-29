package cn.bit101.bitlogin.server.routes

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import cn.bit101.bitlogin.api.jwb.Cjd
import cn.bit101.bitlogin.api.jwb.Score
import cn.bit101.bitlogin.server.model.JwbAllScoreRequest
import cn.bit101.bitlogin.server.model.JwbScoreRequest
import cn.bit101.bitlogin.server.service.AuthServiceExecutor
import cn.bit101.bitlogin.service.JwbCjdLogin
import cn.bit101.bitlogin.service.JwbLogin

private const val SERVICE = "jwb"

fun Routing.jwbRoutes(executor: AuthServiceExecutor) {
    post("/api/jwb/score") {
        val req = call.receive<JwbScoreRequest>()
        val auth = call.request.headers["Authorization"]
        val result: List<Map<String, Any?>> = executor.execute(
            authorization = auth,
            challengeId = req.challengeId,
            loginFactory = { JwbLogin() },
            username = req.username,
            password = req.password,
            serviceName = SERVICE,
        ) { session ->
            Score(session).getScore(kksj = req.kksj, detailed = req.resolvedDetailed())
        }
        call.respond(buildJsonObject("data" to result.toJsonElement()))
    }

    post("/api/jwb/all_score") {
        val req = call.receive<JwbAllScoreRequest>()
        val auth = call.request.headers["Authorization"]
        val result: List<Map<String, Any?>> = executor.execute(
            authorization = auth,
            challengeId = req.challengeId,
            loginFactory = { JwbLogin() },
            username = req.username,
            password = req.password,
            serviceName = SERVICE,
        ) { session ->
            Score(session).getAllScore(detailed = req.detailed)
        }
        call.respond(buildJsonObject("data" to result.toJsonElement()))
    }

    post("/api/jwb/bit101/score") {
        val req = call.receive<JwbScoreRequest>()
        val auth = call.request.headers["Authorization"]
        val result: List<List<String>> = executor.execute(
            authorization = auth,
            challengeId = req.challengeId,
            loginFactory = { JwbLogin() },
            username = req.username,
            password = req.password,
            serviceName = SERVICE,
        ) { session ->
            Score(session).getBit101Score(kksj = req.kksj, detailed = req.resolvedDetailed())
        }
        call.respond(buildJsonObject("msg" to JsonPrimitive("查询成功OvO"), "data" to result.toJsonElement()))
    }

    post("/api/jwb/cjd/img") {
        val req = call.receive<JwbAllScoreRequest>()
        val auth = call.request.headers["Authorization"]
        val url: String = executor.execute(
            authorization = auth,
            challengeId = req.challengeId,
            loginFactory = { JwbCjdLogin() },
            username = req.username,
            password = req.password,
            serviceName = "jwb_cjd",
        ) { session ->
            Cjd(session).getCjd()
        }
        call.respond(buildJsonObject("data" to buildJsonObject("url" to JsonPrimitive(url))))
    }
}

/** Best-effort conversion of `Any?` to a JsonElement tree. */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonPrimitive(null as String?)
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(
        entries.associate { (k, v) -> k.toString() to v.toJsonElement() },
    )
    is List<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(this.toString())
}

internal fun buildJsonObject(vararg pairs: Pair<String, JsonElement>): JsonObject =
    JsonObject(pairs.toMap())
