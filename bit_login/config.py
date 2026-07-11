CONFIG = {
    "common": {
        "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        "content_type_form": "application/x-www-form-urlencoded",
        "content_type_json": "application/json"
    },
    "urls": {
        "base":{
            "sso_api": "https://sso.bit.edu.cn/cas/v1/tickets",
            "sso_login_ui": "https://sso.bit.edu.cn/cas/login",
            "sso_frontend_api": "https://sso.bit.edu.cn/gate",
        },
        "campus":{
            "jwb_cb": "http://jwms.bit.edu.cn/",
            "jwb_referer": "https://jwms.bit.edu.cn/",
            "ibit_cb": "https://ibit.yanhekt.cn/proxy/v1/cas/callback",
            "yanhekt_cb": "https://cbiz.yanhekt.cn/v1/cas/callback",
            "dekt_cb": "https://qcbldekt.bit.edu.cn/cas/login",
            "jxzxehall_app": "https://jxzxehallapp.bit.edu.cn",
            "jxzxehall_auth": "https://jxzxehall.bit.edu.cn/auth-protocol-core/login?service=https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fjwapp%2Fsys%2Fxsfacx%2F*default%2Findex.do",
            "jxzxehall_app_base": "https://jxzxehallapp.bit.edu.cn/jwapp/sys/xsfacx/*default/index.do",
            "jxzxehall_config": "https://jxzxehallapp.bit.edu.cn/jwapp/sys/funauthapp/api/getAppConfig/xsfacx-4766859113956613.do?v=08260885168155102",
            "lib_cas": "https://seatlib.bit.edu.cn/api/cas/cas",
            "lib_auth": "https://seatlib.bit.edu.cn/api/cas/user",
            "lib_referer": "https://seatlib.bit.edu.cn/h5/index.html",
            "lib_origin": "https://seatlib.bit.edu.cn",
            "cxcy_cas": "http://cxcy.bit.edu.cn/pt/HomePage/UnifiedAuthenticationLogin",
            "cxcy_main": "http://cxcy.bit.edu.cn/pt/System/Home/Index",
        },
        "webvpn": {
            "webvpn_origin":"https://webvpn.bit.edu.cn",
            "webvpn_cb": "https://webvpn.bit.edu.cn/login?cas_login=true",
            "webvpn_referer": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3e44ed225397c1e7b0c9ce29b5b/cas/login?service=https:%2F%2Fwebvpn.bit.edu.cn%2Flogin%3Fcas_login%3Dtrue",
            "jwb_cb": "https://webvpn.bit.edu.cn/http/77726476706e69737468656265737421fae04c8f69326144300d8db9d6562d/",
            "jwb_referer": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421fae04c8f69326144300d8db9d6562d/",
            "ibit_cb": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421f9f548886929695e760d82b8d6562d/proxy/v1/cas/callback",
            "yanhekt_cb": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421f3f548866929695e760d82b8d6562d/v1/cas/callback",
            "dekt_cb": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e1f4439023356344300a80b8d6502720f3cfc1/cas/login",
            "jxzxehall_auth": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c72468ba58c1b26316e8e7f6f/auth-protocol-core/login?service=https%3A%2F%2Fjxzxehallapp.bit.edu.cn%2Fjwapp%2Fsys%2Fxsfacx%2F*default%2Findex.do",
            "jxzxehall_app": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d",
            "jxzxehall_app_base": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d/jwapp/sys/xsfacx/*default/index.do",
            "jxzxehall_course": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d/",
            "jxzxehall_config": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d/jwapp/sys/funauthapp/api/getAppConfig/xsfacx-4766859113956613.do?v=08260885168155102",
            "lib_cas": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4/api/cas/cas",
            "lib_auth": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4/api/cas/user",
            "lib_referer": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4/h5/index.html",
            "lib_origin": "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3f240882b396a1e7c019de29d51367b27a4"
        }
    },
    "headers": {
        "base": {
            'Accept': 'application/json, text/javascript, */*; q=0.01',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'Pragma': 'no-cache',
            'Sec-Fetch-Dest': 'empty',
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Site': 'same-origin',
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36',
            'X-Requested-With': 'XMLHttpRequest',
            'sec-ch-ua': '"Not:A-Brand";v="99", "Google Chrome";v="145", "Chromium";v="145"',
            'sec-ch-ua-mobile': '?0',
            'sec-ch-ua-platform': '"macOS"',
        },
        "jwb": {
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Encoding": "gzip, deflate",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "Pragma": "no-cache",
            "Upgrade-Insecure-Requests": "1"
        },
        "jxzxehall": {
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
            "Upgrade-Insecure-Requests": "1"
        },
        "library": {
            "Accept": "application/json, text/plain, */*",
            "X-Requested-With": "XMLHttpRequest"
        },
        "cxcy": {
            'Accept': '*/*',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'Origin': 'http://cxcy.bit.edu.cn',
            'Pragma': 'no-cache',
            'Referer': 'http://cxcy.bit.edu.cn/pt/System/Home/Index',
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36',
            'X-Requested-With': 'XMLHttpRequest',
        }
    }
}
