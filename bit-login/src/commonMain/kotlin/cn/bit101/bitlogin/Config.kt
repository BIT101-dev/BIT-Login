package cn.bit101.bitlogin

/**
 * Static configuration, ported verbatim from Python `bit_login/config.py:CONFIG`.
 *
 * `Urls.active` is mutated at runtime by [cn.bit101.bitlogin.NetworkEnv].
 */
object Config {

    object Common {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        const val CONTENT_TYPE_FORM = "application/x-www-form-urlencoded"
        const val CONTENT_TYPE_JSON = "application/json"
    }

    object Urls {
        object Base {
            const val SSO_API = "https://sso.bit.edu.cn/cas/v1/tickets"
            const val SSO_LOGIN_UI = "https://sso.bit.edu.cn/cas/login"
        }

        val campus: Map<String, String> = mapOf(
            "jwb_cb" to "http://jwms.bit.edu.cn/",
            "jwb_referer" to "https://jwms.bit.edu.cn/",
            "ibit_cb" to "https://ibit.yanhekt.cn/proxy/v1/cas/callback",
            "yanhekt_cb" to "https://cbiz.yanhekt.cn/v1/cas/callback",
            "dekt_cb" to "https://qcbldekt.bit.edu.cn/cas/login",
            "jxzxehall_app" to "https://jxzxehallapp.bit.edu.cn",
            "jxzxehall_auth" to "https://jxzxehall.bit.edu.cn/auth-protocol-core/login?service=https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fjwapp%2Fsys%2Fxsfacx%2F*default%2Findex.do",
            "jxzxehall_app_base" to "https://jxzxehallapp.bit.edu.cn/jwapp/sys/xsfacx/*default/index.do",
            "jxzxehall_config" to "https://jxzxehallapp.bit.edu.cn/jwapp/sys/funauthapp/api/getAppConfig/xsfacx-4766859113956613.do?v=08260885168155102",
            "lib_cas" to "https://seatlib.bit.edu.cn/api/cas/cas",
            "lib_auth" to "https://seatlib.bit.edu.cn/api/cas/user",
            "lib_referer" to "https://seatlib.bit.edu.cn/h5/index.html",
            "lib_origin" to "https://seatlib.bit.edu.cn",
            "cxcy_cas" to "http://cxcy.bit.edu.cn/pt/HomePage/UnifiedAuthenticationLogin",
            "cxcy_main" to "http://cxcy.bit.edu.cn/pt/System/Home/Index",
        )

        val webvpn: Map<String, String> = mapOf(
            "webvpn_origin" to "https://webvpn.bit.edu.cn",
            "webvpn_cb" to "https://webvpn.bit.edu.cn/login?cas_login=true",
            "webvpn_referer" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3e44ed225397c1e7b0c9ce29b5b/cas/login?service=https:%2F%2Fwebvpn.bit.edu.cn%2Flogin%3Fcas_login%3Dtrue",
            "jwb_cb" to "https://webvpn.bit.edu.cn/http/77726476706e69737468656265737421fae04c8f69326144300d8db9d6562d/",
            "jwb_referer" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421fae04c8f69326144300d8db9d6562d/",
            "ibit_cb" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421f9f548886929695e760d82b8d6562d/proxy/v1/cas/callback",
            "yanhekt_cb" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421f3f548866929695e760d82b8d6562d/v1/cas/callback",
            "dekt_cb" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e1f4439023356344300a80b8d6502720f3cfc1/cas/login",
            "jxzxehall_auth" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c72468ba58c1b26316e8e7f6f/auth-protocol-core/login?service=https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fjwapp%2Fsys%2Fxsfacx%2F*default%2Findex.do",
            "jxzxehall_app" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d",
            "jxzxehall_app_base" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d/jwapp/sys/xsfacx/*default/index.do",
            "jxzxehall_course" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d/",
            "jxzxehall_config" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d/jwapp/sys/funauthapp/api/getAppConfig/xsfacx-4766859113956613.do?v=08260885168155102",
            "lib_cas" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4/api/cas/cas",
            "lib_auth" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4/api/cas/user",
            "lib_referer" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4/h5/index.html",
            "lib_origin" to "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4",
        )

        /** Populated by [cn.bit101.bitlogin.NetworkEnv]. campus|webvpn merged with base. */
        val active: MutableMap<String, String> = mutableMapOf()
    }

    object Headers {
        val base: Map<String, String> = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Pragma" to "no-cache",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest",
            "sec-ch-ua" to "\"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"macOS\"",
        )

        val jwb: Map<String, String> = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Encoding" to "gzip, deflate",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Pragma" to "no-cache",
            "Upgrade-Insecure-Requests" to "1",
        )

        val jxzxehall: Map<String, String> = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Upgrade-Insecure-Requests" to "1",
        )

        val library: Map<String, String> = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
        )

        val cxcy: Map<String, String> = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Origin" to "http://cxcy.bit.edu.cn",
            "Pragma" to "no-cache",
            "Referer" to "http://cxcy.bit.edu.cn/pt/System/Home/Index",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest",
        )
    }
}
