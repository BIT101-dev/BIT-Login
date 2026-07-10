import requests
from requests.adapters import HTTPAdapter
import re
from typing import Dict, Any
from .config import CONFIG
from .utils import convert_to_webvpn_url

class login_error(Exception):
    """BIT登录通用异常"""
    pass

class _TimeoutHTTPAdapter(HTTPAdapter):
    """HTTPAdapter that enforces a default request timeout."""
    def __init__(self, *args, **kwargs):
        self._default_timeout = kwargs.pop('timeout', 30)
        super().__init__(*args, **kwargs)

    def send(self, request, **kwargs):
        kwargs.setdefault('timeout', self._default_timeout)
        return super().send(request, **kwargs)

class login:
    """BIT 统一身份认证核心类"""
    def __init__(self, base_url: str = ""):
        self.session = requests.Session()
        # Enforce a default request timeout on all outgoing requests to prevent
        # hung connections from accumulating indefinitely.
        http_adapter = _TimeoutHTTPAdapter(timeout=30)
        https_adapter = _TimeoutHTTPAdapter(timeout=30)
        self.session.mount('http://', http_adapter)
        self.session.mount('https://', https_adapter)
        self.session.headers.update({
            'User-Agent': CONFIG["common"]["ua"],
            # Disable HTTP keep-alive so that TCP connections are closed
            # immediately after each response and are not held open in the
            # connection pool.  Without this, every cached session retains a
            # pool of idle sockets; under load this causes the server-wide TCP
            # connection count to spike until resources are exhausted.
            'Connection': 'close',
        })
        self.base_url = base_url if base_url else CONFIG["urls"]["base"]["sso_api"]
    
    def _get_tgt(self, username, password, retries=0) -> str:
        """获取 Ticket Granting Ticket"""
        headers = {'Content-Type': CONFIG["common"]["content_type_form"]}
        
        r = self.session.post(
            self.base_url, 
            data={'username': username, 'password': password},
            headers=headers
        )
        
        if r.status_code == 401: raise login_error("登录失败: 账号或密码错误")
        if r.status_code != 201: 
            if retries>5: raise login_error(f"TGT获取失败: {r.status_code}")
            return self._get_tgt(username,password,retries+1)

        tgt = r.headers.get('Location')
        if not tgt:
            match = re.search(r'action="([^"]+)"', r.text)
            if match: tgt = match.group(1)
            
        if not tgt: raise login_error("无法解析 TGT URL")
        return tgt


    def login(self, username: str, password: str, callback_url: str = "", webvpn_mode=False,retries=0) -> Dict[str, Any]:
        try:
            # 1. 获取 TGT
            tgt_url = self._get_tgt(username, password)
            
            # 2. 获取 ST 
            headers = {'Content-Type': CONFIG["common"]["content_type_form"]}
            r_st = self.session.post(tgt_url, data={'service': callback_url}, headers=headers)
            
            if r_st.status_code != 200: 
                if retries>5: raise login_error(f"ST获取失败: {r_st.status_code}")
                return self.login(username,password,callback_url,webvpn_mode,retries+1)
            
            ticket = r_st.text.strip()

            # 3. 验证 Ticket 并建立 Session
            if webvpn_mode: callback_url = convert_to_webvpn_url(callback_url)

            separator = "&" if "?" in callback_url else "?"
            callback = f"{callback_url}{separator}ticket={ticket}"


            return {
                "cookie_json": self.session.cookies.get_dict(),
                "cookie": "; ".join([f"{k}={v}" for k, v in self.session.cookies.items()]),
                "callback": callback
            }
        except requests.RequestException as e:
            raise login_error(e)
        except Exception as e:
            raise login_error(str(e))



