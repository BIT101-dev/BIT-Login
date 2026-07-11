import requests
import urllib.parse
import os
from .config import CONFIG
from .utils import check_network_env,convert_to_webvpn_url
from .login import login, login_error, second_auth_required
from urllib.parse import unquote
import functools

network_initialized = False
webvpn_mode = False

class BaseLogin:
    """登录服务基类"""
    def __init__(self, initialize_network=True):
        self._sso_login = login()
        if initialize_network and not network_initialized: self.initialize_network()
        self.initialized = False
        self.result = None
        self._pending_login = None
        self._resume_result = None

    def initialize_network(self):
        global network_initialized,webvpn_mode
        print("Initializing network...",end=" ",flush=True)
        if check_network_env():
            CONFIG["urls"]["active"] = CONFIG["urls"]["campus"].copy()
        else:
            CONFIG["urls"]["active"] = CONFIG["urls"]["webvpn"].copy()
            webvpn_mode = True
        CONFIG["urls"]["active"].update(CONFIG["urls"]["base"])
        network_initialized = True
        print("✅ Done.")

    def _login(self, username, password):
        """具体登录逻辑子类实现"""
        raise NotImplementedError
    
    def patch_webvpn(self,username,password,callback):
        """WebVPN环境下,拿到callback之后直接替换session
        """
        if webvpn_mode:
            callback = convert_to_webvpn_url(callback)
            if not hasattr(self, "_webvpn_login"):
                self._webvpn_login = webvpn_login()
                try:
                    self._webvpn_login.login(username,password)
                except second_auth_required:
                    self._pending_login = self._webvpn_login
                    raise
            self._sso_login.session = self._webvpn_login.get_session()
            self._webvpn_cookie = self._webvpn_login.get_session().cookies
        return callback

    def login(self, username, password):
        """登录(支持链式调用)"""
        self._credentials = (username, password)
        self.result = self._login(username, password)
        self.initialized = True
        return self

    def get_session(self):
        """获取登录后的Session对象"""
        if not self.initialized:
            raise Exception("未登录!")
        return self._sso_login.session

    def get_result(self)->dict:
        """获取登录后的返回结果数据"""
        if not self.initialized:
            raise Exception("未登录!")
        return self.result

    def get_second_auth(self):
        if self._pending_login:
            return self._pending_login.get_second_auth()
        return self._sso_login.get_second_auth()

    def export_second_auth_state(self):
        """Export only the state needed to resume an interrupted login."""
        return {
            "network_mode": "webvpn" if webvpn_mode else "campus",
            "sso_login": self._sso_login.export_state(),
            "pending_login": (
                self._pending_login.export_second_auth_state()
                if self._pending_login else None
            ),
            "resume_result": self._resume_result,
        }

    def restore_second_auth_state(self, state, username, password):
        """Restore an interrupted login without retaining credentials in state."""
        global network_initialized, webvpn_mode
        if not isinstance(state, dict) or not isinstance(state.get("sso_login"), dict):
            raise login_error("服务二次认证状态格式无效")
        network_mode = state.get("network_mode")
        if network_mode not in ("campus", "webvpn"):
            raise login_error("服务二次认证网络状态无效")
        webvpn_mode = network_mode == "webvpn"
        CONFIG["urls"]["active"] = CONFIG["urls"][network_mode].copy()
        CONFIG["urls"]["active"].update(CONFIG["urls"]["base"])
        network_initialized = True
        self._sso_login.restore_state(state["sso_login"])
        self._credentials = (username, password)
        self._resume_result = state.get("resume_result")
        pending_state = state.get("pending_login")
        if pending_state is not None:
            pending = webvpn_login(initialize_network=False)
            pending.restore_second_auth_state(pending_state, username, password)
            self._pending_login = pending
            self._webvpn_login = pending
        else:
            self._pending_login = None
        self.initialized = False
        self.result = None
        return self

    def send_sms_code(self):
        if self._pending_login:
            return self._pending_login.send_sms_code()
        return self._sso_login.send_sms_code()

    def verify_sms_code(self, code):
        if self._pending_login:
            self._pending_login.verify_sms_code(code)
            return self._resume_after_nested_auth()
        self.result = self._finish_second_auth(self._sso_login.verify_sms_code(code))
        self.initialized = True
        return self

    def begin_dingtalk_qr(self):
        if self._pending_login:
            return self._pending_login.begin_dingtalk_qr()
        return self._sso_login.begin_dingtalk_qr()

    def poll_dingtalk_qr(self):
        if self._pending_login:
            result = self._pending_login.poll_dingtalk_qr()
            if isinstance(result, dict) and result.get("status") == "waiting":
                return result
            return self._resume_after_nested_auth()
        result = self._sso_login.poll_dingtalk_qr()
        if result.get("status") == "waiting":
            return result
        self.result = self._finish_second_auth(result)
        self.initialized = True
        return self

    def _finish_second_auth(self, result):
        username, password = self._credentials
        self._resume_result = result
        return self._complete_login(result, username, password)

    def _resume_after_nested_auth(self):
        self._pending_login = None
        username, password = self._credentials
        self.result = self._complete_login(self._resume_result, username, password)
        self._resume_result = None
        self.initialized = True
        return self

    def _get_cookies_result(self):
        """通用格式化Cookie输出"""
        cookies = self._sso_login.session.cookies.get_dict()
        return {
            "cookie_json": cookies,
            "cookie": "; ".join([f"{k}={v}" for k, v in cookies.items()])
        }

class webvpn_login(BaseLogin):
    """WebVPN 服务"""
    def _login(self, username, password):
        """WebVPN登录逻辑"""
        res = self._sso_login.login(username, password, callback_url=CONFIG["urls"]["webvpn"]["webvpn_cb"], webvpn_mode=True)
        return self._complete_login(res, username, password)

    def _complete_login(self, res, username, password):
        if res["callback"][0]=='/': res["callback"] = CONFIG["urls"]["webvpn"]["webvpn_origin"]+res["callback"]
        self._sso_login.session.get(res["callback"])
        res.update(self._get_cookies_result())
        return res

class jwb_login(BaseLogin):
    """教务系统"""

    def _request_login_step(self, label, url, headers):
        response = self._sso_login.session.get(url, headers=headers, allow_redirects=False)
        if response.status_code >= 500:
            raise login_error(f"jwb: {label} 失败 HTTP {response.status_code}")
        return response

    def _login(self, username, password):
        """教务系统登录逻辑"""
        res = self._sso_login.login(username, password, callback_url=CONFIG["urls"]["campus"]["jwb_cb"])
        return self._complete_login(res, username, password)

    def _complete_login(self, res, username, password):
        self._resume_result = res
        res["callback"] = self.patch_webvpn(username,password,res["callback"])
        self._resume_result = None
        self._sso_login.session.trust_env = False
        headers = CONFIG["headers"]["jwb"].copy()
        headers["Referer"] = f'{CONFIG["urls"]["base"]["sso_login_ui"]}?service={urllib.parse.quote(CONFIG["urls"]["campus"]["jwb_cb"])}'
        self._sso_login.session.headers.update(headers)
        self._request_login_step("preflight-root", CONFIG["urls"]["active"]["jwb_cb"], headers)
        response = self._request_login_step("callback", res["callback"], headers)
        location = response.headers.get("Location")
        if location:
            response = self._request_login_step("landing", urllib.parse.urljoin(response.url, location), headers)
        location = response.headers.get("Location")
        if location:
            response = self._request_login_step("login-to-xk", urllib.parse.urljoin(response.url, location), headers)
        location = response.headers.get("Location")
        if location:
            response = self._request_login_step("main", urllib.parse.urljoin(response.url, location), headers)
        if "sso.bit.edu.cn/cas/login" in response.url:
            raise login_error("jwb: 登录回调后仍停留在统一身份认证页")
        return self._get_cookies_result()

class jwb_cjd_login(BaseLogin):
    """教务系统成绩单"""
    def _login(self, username, password):
        r1 = self._sso_login.session.get("https://jwb.bit.edu.cn/cjd/Account/ExternalLogin",allow_redirects=False)
        res = self._sso_login.login(username, password, callback_url=unquote(r1.headers["Location"].split("?service=")[-1]))
        return self._complete_login(res, username, password)

    def _complete_login(self, res, username, password):
        self._sso_login.session.get(res["callback"])
        return self._get_cookies_result()

class jxzxehall_login(BaseLogin):
    """教学中心/一站式大厅(直连)"""
    def _login(self, username, password):
        """教学中心登录逻辑"""
        headers = CONFIG["headers"]["jxzxehall"]
        r_pre = requests.get(CONFIG["urls"]["campus"]["jxzxehall_auth"], allow_redirects=False, headers=headers)
        try:
            raw_service = r_pre.headers["Location"].split("?service=")[-1]
            callback_url = urllib.parse.unquote(raw_service)
        except Exception:
            raise login_error("jxzxehall: 解析 service url 失败")
        res = self._sso_login.login(username, password, callback_url=callback_url)
        return self._complete_login(res, username, password)

    def _complete_login(self, res, username, password):
        headers = CONFIG["headers"]["jxzxehall"]
        self._resume_result = res
        res["callback"] = self.patch_webvpn(username,password,res["callback"])
        self._resume_result = None
        self._sso_login.session.get(res["callback"], headers=headers)
        self._sso_login.session.get(CONFIG["urls"]["active"]["jxzxehall_app_base"])
        self._sso_login.session.get(CONFIG["urls"]["active"]["jxzxehall_config"], headers=headers)
        return self._get_cookies_result()

class ibit_login(BaseLogin):
    """iBIT(直连)"""
    def _login(self, username, password):
        """iBIT登录逻辑"""
        data = self._sso_login.login(username, password, callback_url=CONFIG["urls"]["campus"]["ibit_cb"])
        return self._complete_login(data, username, password)

    def _complete_login(self, data, username, password):
        try:
            r_login = self._sso_login.session.get(data["callback"], allow_redirects=False)
            data["callback"] = r_login.headers.get('Location', data["callback"])
            qs = urllib.parse.urlparse(data["callback"]).query
            badge = urllib.parse.parse_qs(qs).get('badgeFromPc', [''])[0]
            if badge:
                data['cookie_json']['badge_2'] = badge
                data['cookie'] += f"; badge_2={badge}"
                self._sso_login.session.headers.update({"Badge": badge,'badge': badge, 'Xdomain-Client': 'web_user',"Referer":f"https://ibit.yanhekt.cn/desktop?badgeFromPc={badge}"}) # 自动携带
        except Exception:
            pass
        return data

class yanhekt_login(BaseLogin):
    """延河课堂"""
    def _login(self, username, password):
        """延河课堂登录逻辑"""
        data = self._sso_login.login(username, password, callback_url=CONFIG["urls"]["campus"]["yanhekt_cb"])
        return self._complete_login(data, username, password)

    def _complete_login(self, data, username, password):
        token = ""
        try:
            r_login = self._sso_login.session.get(data["callback"], allow_redirects=False)
            data["callback"] = r_login.headers.get('Location', data["callback"])
            qs = urllib.parse.urlparse(data["callback"]).query
            token = urllib.parse.parse_qs(qs).get('token', [''])[0]
            if not token and 'token=' in data['callback']:
                token = data['callback'].split('token=')[1].split('&')[0]
            self._sso_login.session.headers.update({
                'Authorization': f'Bearer {token}',
                'Origin': 'https://www.yanhekt.cn',
                'Referer': 'https://www.yanhekt.cn/',
                'Xdomain-Client': 'web_user',
            })
        except Exception:
            pass
        if not token: 
            raise login_error("Yanhekt Token 解析失败")
        result = self._get_cookies_result()
        result["token"] = token
        return result

class library_login(BaseLogin):
    """图书馆"""
    def _login(self, username, password):
        """图书馆登录逻辑"""
        lib_headers = CONFIG["headers"]["library"].copy()
        lib_headers.update({
            'Content-Type': CONFIG["common"]["content_type_json"],
            'Origin': CONFIG["urls"]["campus"]["lib_origin"],
            'Referer': CONFIG["urls"]["campus"]["lib_referer"]
        })
        self._sso_login.session.headers.update(lib_headers)
        try: 
            self._sso_login.session.get(CONFIG["urls"]["campus"]["lib_cas"])
        except: 
            pass
        cas_service = CONFIG["urls"]["campus"]["lib_cas"]
        self._sso_login.session.headers['Referer'] = f"{CONFIG['urls']['base']['sso_login_ui']}?service={urllib.parse.quote(cas_service)}"
        data = self._sso_login.login(username, password, callback_url=cas_service)
        return self._complete_login(data, username, password)

    def _complete_login(self, data, username, password):
        cas_service = CONFIG["urls"]["campus"]["lib_cas"]
        r_login = self._sso_login.session.get(data["callback"], allow_redirects=False)
        data["callback"] = r_login.headers.get('Location', data["callback"])
        callback_url = data['callback']
        cas_ticket = ""
        if 'cas=' in callback_url:
            cas_ticket = callback_url.split('cas=')[1].split('&')[0]
        else:
            try:
                r_retry = self._sso_login.session.get(cas_service, allow_redirects=False)
                loc = r_retry.headers.get('Location', '')
                if 'cas=' in loc: 
                    cas_ticket = loc.split('cas=')[1].split('&')[0]
            except: 
                pass
        if not cas_ticket: 
            raise login_error(f"图书馆登录失败: 未获取到 CAS Ticket (Url: {callback_url})")
        try:
            self._sso_login.session.headers['Referer'] = CONFIG["urls"]["campus"]["lib_referer"]
            resp = self._sso_login.session.post(CONFIG["urls"]["campus"]["lib_auth"], json={'cas': cas_ticket}).json()
            if resp.get("code") != 1:
                raise login_error(f"图书馆授权失败: {resp.get('msg')}")
            res = self._get_cookies_result()
            res.update({
                "user_info": resp.get("member", {}),
                "token": resp.get("member", {}).get("token")
            })
            return res
        except Exception as e:
            raise login_error(f"图书馆 API 解析失败: {e}")

class dekt_login(BaseLogin):
    """第二课堂"""
    def _login(self, username, password):
        """第二课堂登录逻辑(尚不可用)"""
        data = self._sso_login.login(username, password, callback_url=CONFIG["urls"]["campus"]["dekt_cb"])
        return self._complete_login(data, username, password)

    def _complete_login(self, data, username, password):
        self._sso_login.session.get(data["callback"], allow_redirects=True)
        return self._get_cookies_result()

import functools

class cxcy_login(BaseLogin):
    """大创系统(直连)"""
    def _login(self, username, password):
        """大创系统登录逻辑"""
        data = self._sso_login.login(username, password, callback_url=CONFIG["urls"]["campus"]["cxcy_cas"])
        return self._complete_login(data, username, password)

    def _complete_login(self, data, username, password):
        r_login = self._sso_login.session.get(data["callback"], allow_redirects=False)
        data["callback"] = r_login.headers.get('Location', data["callback"])
        self._sso_login.session.get(data["callback"], allow_redirects=True)
        self._sso_login.session.get(CONFIG["urls"]["campus"]["cxcy_main"])

        session = self._sso_login.session
        session.headers.update(CONFIG["headers"]["cxcy"])
        original_post = session.post

        @functools.wraps(original_post)
        def wrapped_post(url, data=None, json=None, **kwargs):
            if "cxcy.bit.edu.cn" in url:
                token = session.cookies.get('__RequestVerificationToken_L3B00')
                if token:
                    headers = kwargs.get('headers', CONFIG["headers"]["cxcy"].copy())
                    headers['__RequestVerificationToken'] = token
                    kwargs['headers'] = headers
                    
                    if data is None and json is None and 'files' not in kwargs:
                        data = {}
                    if isinstance(data, dict):
                        data['__RequestVerificationToken'] = token

            return original_post(url, data=data, json=json, **kwargs)

        session.post = wrapped_post

        print(session.post("http://cxcy.bit.edu.cn/pt/System/Platform/GetEffectivePlatformList").status_code)
        
        return self._get_cookies_result()
    
