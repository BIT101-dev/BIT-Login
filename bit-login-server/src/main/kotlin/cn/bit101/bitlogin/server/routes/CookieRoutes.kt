package cn.bit101.bitlogin.server.routes

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import cn.bit101.bitlogin.server.model.BaseCredentials
import cn.bit101.bitlogin.server.service.AuthServiceExecutor
import cn.bit101.bitlogin.service.JwbCjdLogin
import cn.bit101.bitlogin.service.JwbLogin
import cn.bit101.bitlogin.service.JxzxehallLogin

/** Returns session cookies for the named service. Mirrors Python `/api/{svc}/cookies` endpoints. */
fun Routing.cookieRoutes(executor: AuthServiceExecutor) {
    post("/api/jwb/cookies") {
        val req = call.receive<BaseCredentials>()
        val auth = call.request.headers["Authorization"]
        val cookies = cookieResponse(executor, "jwb", ::JwbLogin, req.username, req.password, auth, req.challengeId)
        call.respond(cookies)
    }

    post("/api/jwb/cjd/cookies") {
        val req = call.receive<BaseCredentials>()
        val auth = call.request.headers["Authorization"]
        val cookies = cookieResponse(executor, "jwb_cjd", ::JwbCjdLogin, req.username, req.password, auth, req.challengeId)
        call.respond(cookies)
    }

    post("/api/jxzxehall/cookies") {
        val req = call.receive<BaseCredentials>()
        val auth = call.request.headers["Authorization"]
        val cookies = cookieResponse(executor, "jxzxehall", ::JxzxehallLogin, req.username, req.password, auth, req.challengeId)
        call.respond(cookies)
    }
}

private suspend fun cookieResponse(
    executor: AuthServiceExecutor,
    serviceName: String,
    loginFactory: () -> cn.bit101.bitlogin.service.BaseLogin,
    username: String,
    password: String,
    authorization: String?,
    challengeId: String?,
): kotlinx.serialization.json.JsonObject {
    return try {
        val session = executor.execute(
            authorization = authorization,
            challengeId = challengeId,
            loginFactory = loginFactory,
            username = username,
            password = password,
            serviceName = serviceName,
        ) { it }
        val cookies = session.cookieMap()
        val cookieStr = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        buildJsonObject(
            "data" to cookies.toJsonElement(),
            "cookie_str" to JsonPrimitive(cookieStr),
        )
    } catch (e: Exception) {
        if (e is cn.bit101.bitlogin.server.plugins.HttpException || e is cn.bit101.bitlogin.login.LoginError) throw e
        throw cn.bit101.bitlogin.server.plugins.HttpException(500, "Failed to extract cookies from session")
    }
}
