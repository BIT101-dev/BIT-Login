package cn.bit101.bitlogin.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.utils.io.jvm.javaio.copyTo
import cn.bit101.bitlogin.server.ics.IcsFileStore
import cn.bit101.bitlogin.server.plugins.HttpException

fun Routing.icsRoutes(icsStore: IcsFileStore) {
    get("/tmp/{filename}") {
        val filename = call.parameters["filename"] ?: ""
        if (!filename.endsWith(".ics")) {
            call.response.status(HttpStatusCode.Forbidden)
            call.respond(mapOf("detail" to "Forbidden"))
            return@get
        }
        val file = icsStore.get(filename)
            ?: throw HttpException(404, "File not found or expired")
        call.response.header(
            "Content-Disposition",
            "attachment; filename=\"课程表.ics\"",
        )
        call.respondOutputStream(ContentType.parse("text/calendar")) {
            file.toFile().inputStream().use { input -> input.copyTo(this) }
        }
    }
}
