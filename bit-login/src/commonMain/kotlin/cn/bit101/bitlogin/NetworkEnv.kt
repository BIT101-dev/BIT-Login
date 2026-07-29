package cn.bit101.bitlogin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import cn.bit101.bitlogin.util.NetEnv
import cn.bit101.bitlogin.util.ioDispatcher

/**
 * Detects campus vs. WebVPN network environment and populates [Config.Urls.active].
 * Equivalent to Python `bit_login/service.py:initialize_network`.
 */
object NetworkEnv {
    @Volatile private var initialized = false
    @Volatile var webvpnMode = false
        private set

    private val initMutex = Mutex()

    suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            val onCampus = withContext(ioDispatcher) { NetEnv.checkNetworkEnv() }
            Config.Urls.active.clear()
            Config.Urls.active.putAll(if (onCampus) Config.Urls.campus else Config.Urls.webvpn)
            if (!onCampus) webvpnMode = true
            Config.Urls.active["sso_api"] = Config.Urls.Base.SSO_API
            Config.Urls.active["sso_login_ui"] = Config.Urls.Base.SSO_LOGIN_UI
            initialized = true
        }
    }
}
