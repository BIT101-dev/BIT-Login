package cn.bit101.bitlogin.server.routes

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive
import cn.bit101.bitlogin.api.jxzxehall.Course
import cn.bit101.bitlogin.api.jxzxehall.Credit
import cn.bit101.bitlogin.api.jxzxehall.JxzxehallDataError
import cn.bit101.bitlogin.server.ics.IcsFileStore
import cn.bit101.bitlogin.server.model.BaseCredentials
import cn.bit101.bitlogin.server.model.JxzxehallCoursesRequest
import cn.bit101.bitlogin.server.plugins.HttpException
import cn.bit101.bitlogin.server.service.AuthServiceExecutor
import cn.bit101.bitlogin.service.JxzxehallLogin

private const val SERVICE = "jxzxehall"

fun Routing.jxzxehallRoutes(executor: AuthServiceExecutor, icsStore: IcsFileStore) {
    post("/api/jxzxehall/student_data") {
        val req = call.receive<BaseCredentials>()
        val auth = call.request.headers["Authorization"]
        val result = executor.execute(
            authorization = auth,
            challengeId = req.challengeId,
            loginFactory = { JxzxehallLogin() },
            username = req.username,
            password = req.password,
            serviceName = SERVICE,
        ) { session ->
            Credit(session).getStudentData()
        }
        call.respond(buildJsonObject("data" to result.toJsonElement()))
    }

    post("/api/jxzxehall/credit") {
        val req = call.receive<BaseCredentials>()
        val auth = call.request.headers["Authorization"]
        val result = executor.execute(
            authorization = auth,
            challengeId = req.challengeId,
            loginFactory = { JxzxehallLogin() },
            username = req.username,
            password = req.password,
            serviceName = SERVICE,
        ) { session ->
            Credit(session).getCredit()
        }
        call.respond(buildJsonObject("data" to result.toJsonElement()))
    }

    post("/api/jxzxehall/courses") {
        val req = call.receive<JxzxehallCoursesRequest>()
        val auth = call.request.headers["Authorization"]
        val result = try {
            executor.execute(
                authorization = auth,
                challengeId = req.challengeId,
                loginFactory = { JxzxehallLogin() },
                username = req.username,
                password = req.password,
                serviceName = SERVICE,
            ) { session ->
                Course(session).getCourses(kksj = req.kksj)
            }
        } catch (e: JxzxehallDataError) {
            throw HttpException(422, e.message ?: "jxzxehall data error")
        }
        call.respond(buildJsonObject("data" to result.toJsonElement()))
    }

    post("/api/jxzxehall/schedule_ics") {
        val req = call.receive<JxzxehallCoursesRequest>()
        val auth = call.request.headers["Authorization"]
        val (icsContent, note) = try {
            executor.execute(
                authorization = auth,
                challengeId = req.challengeId,
                loginFactory = { JxzxehallLogin() },
                username = req.username,
                password = req.password,
                serviceName = SERVICE,
            ) { session ->
                Course(session).generateIcs(kksj = req.kksj)
            }
        } catch (e: JxzxehallDataError) {
            throw HttpException(422, e.message ?: "jxzxehall data error")
        }
        val (url, _) = icsStore.store(icsContent)
        call.respond(
            buildJsonObject(
                "url" to JsonPrimitive(url),
                "note" to JsonPrimitive(note),
                "msg" to JsonPrimitive("获取成功OvO"),
            ),
        )
    }
}
