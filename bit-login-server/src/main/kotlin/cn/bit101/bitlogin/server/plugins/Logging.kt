package cn.bit101.bitlogin.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging

fun Application.configureLogging() {
    install(CallLogging)
}
