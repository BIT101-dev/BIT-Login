package cn.bit101.bitlogin.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import cn.bit101.bitlogin.server.config.AppConfig

fun Application.configureCors(appConfig: AppConfig) {
    install(DefaultHeaders)
    install(CORS) {
        allowCredentials = true
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Head)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Challenge-Token")
        allowHeader(HttpHeaders.Cookie)
        maxAgeInSeconds = 3600
    }
}
