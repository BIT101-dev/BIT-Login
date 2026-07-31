package cn.bit101.bitlogin.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.NetworkEnv
import cn.bit101.bitlogin.login.SsoLogin

/**
 * 乐学 (Moodle) login. Mirrors Python `bit_login.service.lexue_login` semantics.
 *
 * Moodle 走统一身份认证, 没有独立的账号密码认证。这里复用 SSO 会话, 通过 CAS
 * 静默登录为乐学建立独立会话 (MoodleSession Cookie), 之后 [cn.bit101.bitlogin.api.lexue.LexueCalendar]
 * 可直接使用该会话而无需每次依赖 CAS 重定向链。
 *
 * Flow:
 *  1. SSO login with lexue 的登录页作为 service
 *  2. (webvpn mode) patch webvpn + 交换会话
 *  3. GET 带 ticket 的回调, 触发 Moodle 校验并下发 MoodleSession
 */
class LexueLogin(sso: SsoLogin = SsoLogin()) : BaseLogin(sso) {

    private val htmlHeaders: Map<String, String> = Config.Headers.jwb

    private fun lexueServiceUrl(): String = activeUrl("lexue").trimEnd('/') + "/login/index.php"

    override suspend fun doLogin(username: String, password: String): JsonObject {
        val res = sso.login(username, password, callbackUrl = lexueServiceUrl())
        val patchedCallback = patchWebvpn(username, password, res.callback)
        sso.session.get(patchedCallback, headers = htmlHeaders, allowRedirects = true)
        return cookiesResult()
    }

    /**
     * 使用当前已认证的 SSO 会话为乐学建立 Moodle 会话 (拿到 MoodleSession Cookie)。
     * 尽力而为: 失败/超时不影响主登录流程, 只意味着乐学需在请求时重新走 CAS。
     */
    suspend fun establishSession() {
        try {
            withTimeout(10_000L) {
                if (NetworkEnv.webvpnMode) copyInternalCookiesToWebvpn()
                val base = activeUrl("lexue").trimEnd('/')
                sso.session.get("$base/", headers = htmlHeaders, allowRedirects = true)
            }
        } catch (e: TimeoutCancellationException) {
            // 静默登录超时, 忽略
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 乐学会话建立失败不影响主登录
        }
    }

    // WebVPN 模式下, 校内域名的 Cookie (如 sso.bit.edu.cn 的 SESSION) 不会发往 webvpn 主机,
    // 复制一份到 webvpn 域名下, 让网关转发给内部 CAS, 静默登录才能识别会话。
    private suspend fun copyInternalCookiesToWebvpn() {
        val webvpnHost = Config.Urls.webvpn.getValue("webvpn_origin").substringAfter("://")
        sso.session.cookieDetails()
            .filter { it.domain.trimStart('.').lowercase().endsWith(".bit.edu.cn") && it.domain.trimStart('.').lowercase() != webvpnHost }
            .forEach { cookie ->
                sso.session.addCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = webvpnHost,
                    path = "/",
                    secure = cookie.secure,
                    expiresEpochSeconds = cookie.expires,
                )
            }
    }
}
