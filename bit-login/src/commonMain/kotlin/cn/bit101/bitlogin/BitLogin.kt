package cn.bit101.bitlogin

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

/**
 * Top-level facade for the bit-login SDK, mirroring Python `import bit_login`.
 *
 * Each property returns a fresh login instance (one-shot use per `login()` chain).
 */
object BitLogin {
    const val VERSION = "4.0.0"
    const val AUTHOR = "Teclab"
    const val EMAIL = "admin@teclab.org.cn"
    const val DESCRIPTION = "北京理工大学统一身份认证登录库"

    fun webvpnLogin(): WebVpnLogin = WebVpnLogin()
    fun jwbLogin(): JwbLogin = JwbLogin()
    fun jwbCjdLogin(): JwbCjdLogin = JwbCjdLogin()
    fun jxzxehallLogin(): JxzxehallLogin = JxzxehallLogin()
    fun ibitLogin(): IbitLogin = IbitLogin()
    fun yanhektLogin(): YanhektLogin = YanhektLogin()
    fun libraryLogin(): LibraryLogin = LibraryLogin()
    fun dektLogin(): DektLogin = DektLogin()
    fun cxcyLogin(): CxcyLogin = CxcyLogin()
}

/** Tag marker for service login classes. */
typealias ServiceLogin = BaseLogin
