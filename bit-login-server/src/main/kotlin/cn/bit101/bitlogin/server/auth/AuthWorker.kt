package cn.bit101.bitlogin.server.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.login.SsoLogin
import cn.bit101.bitlogin.service.BaseLogin
import cn.bit101.bitlogin.service.CxcyLogin
import cn.bit101.bitlogin.service.DektLogin
import cn.bit101.bitlogin.service.IbitLogin
import cn.bit101.bitlogin.service.JwbCjdLogin
import cn.bit101.bitlogin.service.JwbLogin
import cn.bit101.bitlogin.service.JxzxehallLogin
import cn.bit101.bitlogin.service.LibraryLogin
import cn.bit101.bitlogin.service.WebVpnLogin
import cn.bit101.bitlogin.service.YanhektLogin
import cn.bit101.bitlogin.sso.SmsCodeCallback
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(AuthWorker::class.java)

object AuthServices {
    val factories: Map<String, (SsoLogin) -> BaseLogin> = mapOf(
        "webvpn" to { sso -> WebVpnLogin(sso) },
        "jwb" to { sso -> JwbLogin(sso) },
        "jwb_cjd" to { sso -> JwbCjdLogin(sso) },
        "jxzxehall" to { sso -> JxzxehallLogin(sso) },
        "ibit" to { sso -> IbitLogin(sso) },
        "yanhekt" to { sso -> YanhektLogin(sso) },
        "library" to { sso -> LibraryLogin(sso) },
        "dekt" to { sso -> DektLogin(sso) },
        "cxcy" to { sso -> CxcyLogin(sso) },
    )

    val names: Set<String> get() = factories.keys
}

class AuthWorker(
    private val store: ChallengeStore,
    private val connectTimeoutMs: Long = 5_000L,
    private val socketTimeoutMs: Long = 25_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
) {
    suspend fun startAuthentication(username: String, password: String, services: List<String>, subject: String): ChallengeHandle {
        val handle = store.create(services, subject)
        scope.launch {
            try {
                runAuthentication(handle.challengeId, username, password, services)
            } catch (e: Exception) {
                store.fail(handle.challengeId, e)
            }
        }
        return handle
    }

    private suspend fun runAuthentication(challengeId: String, username: String, password: String, services: List<String>) {
        val totalStart = System.currentTimeMillis()
        val smsCallback: SmsCodeCallback = { ctx -> store.waitForSms(challengeId, ctx) }
        var seedSession: HttpClient? = null
        for (serviceName in services) {
            val serviceStart = System.currentTimeMillis()
            val session = HttpClient(connectTimeoutMs = connectTimeoutMs, socketTimeoutMs = socketTimeoutMs)
            seedSession?.cookieDetails()?.forEach { c ->
                session.addCookie(c.name, c.value, c.domain, c.path, c.secure)
            }
            val sso = SsoLogin(session = session, smsCodeCallback = smsCallback)
            val factory = AuthServices.factories[serviceName]
                ?: throw ChallengeError("unknown service: $serviceName")
            val login = factory(sso)
            login.login(username, password)
            if (seedSession == null) seedSession = login.getSession()
            store.storeService(challengeId, serviceName, login.getSession(), login.getResult())
            logger.info("challenge {} service {} login took {}ms", challengeId, serviceName, System.currentTimeMillis() - serviceStart)
        }
        store.complete(challengeId)
        logger.info("challenge {} authentication completed in {}ms for {} services", challengeId, System.currentTimeMillis() - totalStart, services.size)
    }
}
