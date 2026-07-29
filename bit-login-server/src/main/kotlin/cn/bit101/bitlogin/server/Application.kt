package cn.bit101.bitlogin.server

import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import cn.bit101.bitlogin.server.auth.AuthWorker
import cn.bit101.bitlogin.server.auth.ChallengeStore
import cn.bit101.bitlogin.server.config.AppConfig
import cn.bit101.bitlogin.server.ics.IcsFileStore
import cn.bit101.bitlogin.server.plugins.configureCors
import cn.bit101.bitlogin.server.plugins.configureLogging
import cn.bit101.bitlogin.server.plugins.configureSerialization
import cn.bit101.bitlogin.server.plugins.configureStatusPages
import cn.bit101.bitlogin.server.routes.authRoutes
import cn.bit101.bitlogin.server.routes.cookieRoutes
import cn.bit101.bitlogin.server.routes.icsRoutes
import cn.bit101.bitlogin.server.routes.jwbRoutes
import cn.bit101.bitlogin.server.routes.jxzxehallRoutes
import cn.bit101.bitlogin.server.routes.rootRoute
import cn.bit101.bitlogin.server.service.AuthServiceExecutor

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = config.host) {
        mainModule(config)
    }.start(wait = true)
}

fun Application.mainModule(appConfig: AppConfig) {
    val icsStore = IcsFileStore(baseUrl = appConfig.baseUrl)
    val challengeStore = ChallengeStore(
        database = appConfig.authDbPath,
        pendingTtl = appConfig.authChallengeTtl,
        readyTtl = appConfig.authSessionTtl,
    )
    val authWorker = AuthWorker(
        challengeStore,
        connectTimeoutMs = appConfig.connectTimeoutMs,
        socketTimeoutMs = appConfig.socketTimeoutMs,
    )
    val authExecutor = AuthServiceExecutor(challengeStore, authWorker)

    configureSerialization()
    configureStatusPages()
    configureLogging()
    configureCors(appConfig)
    routing {
        rootRoute()
        authRoutes(authWorker, challengeStore)
        jwbRoutes(authExecutor)
        jxzxehallRoutes(authExecutor, icsStore)
        cookieRoutes(authExecutor)
        icsRoutes(icsStore)
    }

    // Background cleanup
    launch { icsStore.cleanupLoop() }
    launch {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            challengeStore.cleanup()
        }
    }
}

