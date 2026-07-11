import requests
import re
import base64
import hashlib
import json
import os
import platform
import time
from typing import Dict, Any
from urllib.parse import urljoin, urlparse
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from .config import CONFIG
from .second_auth import SecondAuthFlow

class login_error(Exception):
    """BIT登录通用异常"""
    pass

class second_auth_required(login_error):
    """密码校验成功，但登录需要二次认证。"""
    pass

class login:
    """BIT 统一身份认证核心类"""
    def __init__(self, base_url: str = ""):
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': CONFIG["common"]["ua"]
        })
        self.base_url = base_url if base_url else CONFIG["urls"]["base"]["sso_login_ui"]
        parsed_base_url = urlparse(self.base_url)
        self.sso_origin = "{}://{}".format(parsed_base_url.scheme, parsed_base_url.netloc)
        self.cas_url = urljoin(self.sso_origin, "/cas")
        self.sso_cookie_domain = parsed_base_url.hostname
        if parsed_base_url.path.rstrip("/").endswith("/cas/v1/tickets"):
            self.base_url = self.cas_url + "/login"
        self.second_auth = SecondAuthFlow(
            self.session, self.base_url, self.cas_url, login_error, self._complete_second_auth
        )

    @staticmethod
    def _get_page_value(html, element_id):
        pattern = r'<[^>]+id=["\']{}["\'][^>]*>([^<]*)<'.format(re.escape(element_id))
        match = re.search(pattern, html)
        return match.group(1).strip() if match else ""

    @staticmethod
    def _encrypt(value, crypto_key):
        try:
            key = base64.b64decode(crypto_key, validate=True)
            padder = padding.PKCS7(algorithms.AES.block_size).padder()
            padded = padder.update(value.encode("utf-8")) + padder.finalize()
            encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
            encrypted = encryptor.update(padded) + encryptor.finalize()
            return base64.b64encode(encrypted).decode("ascii")
        except (ValueError, TypeError) as e:
            raise login_error("统一身份认证密码加密参数无效: {}".format(e))

    def _get_risk_payload(self):
        sha256 = lambda value: hashlib.sha256(value.encode("utf-8")).hexdigest()
        values = {
            "fonts": "[]",
            "deviceMemory": "8",
            "hardwareConcurrency": str(os.cpu_count() or 1),
            "timezone": '"Asia/Shanghai"',
            "cpuClass": '"unknown"',
            "platform": json.dumps(platform.system() + " " + platform.machine()),
            "language": '"zh-CN"',
            "screenResolution": "[1920,1080]",
        }
        device = sha256(str(time.time()))
        self.session.cookies.set("device", device, domain=self.sso_cookie_domain, path="/")
        fingerprint = {key: sha256(value) for key, value in values.items()}
        for key in ("timezone", "platform", "language", "screenResolution"):
            fingerprint[key] = values[key]
        fingerprint.update({
            "localgroupId": "",
            "fingerprint": sha256("".join(values.values())),
            "cookieValue": device,
            "userAgent": self.session.headers["User-Agent"],
            "platformAuthenticator": "nonsupport",
        })
        response = self.session.post(
            urljoin(self.sso_origin, "/ustc-rba-front/fp"),
            json=fingerprint,
        )
        try:
            risk_payload = response.json().get("responsetoken", "")
        except ValueError:
            risk_payload = ""
        if response.status_code != 200 or not risk_payload:
            raise login_error("统一身份认证设备风控初始化失败")
        return risk_payload

    def _result(self, callback):
        return {
            "cookie_json": self.session.cookies.get_dict(),
            "cookie": "; ".join(["{}={}".format(k, v) for k, v in self.session.cookies.items()]),
            "callback": callback,
        }

    def export_state(self):
        """Export the serializable state required to continue second auth."""
        cookies = []
        for cookie in self.session.cookies:
            cookies.append({
                "name": cookie.name,
                "value": cookie.value,
                "domain": cookie.domain,
                "path": cookie.path,
                "secure": cookie.secure,
                "expires": cookie.expires,
                "rest": dict(cookie._rest),
            })
        return {
            "base_url": self.base_url,
            "headers": dict(self.session.headers),
            "cookies": cookies,
            "second_auth_state": dict(self.second_auth.state) if self.second_auth.state else None,
        }

    def restore_state(self, state):
        """Restore state previously returned by export_state()."""
        if not isinstance(state, dict):
            raise login_error("二次认证状态格式无效")
        if state.get("base_url") != self.base_url:
            raise login_error("二次认证登录地址不匹配")
        headers = state.get("headers")
        cookies = state.get("cookies")
        second_auth_state = state.get("second_auth_state")
        if not isinstance(headers, dict) or not isinstance(cookies, list):
            raise login_error("二次认证会话状态格式无效")
        if second_auth_state is not None and not isinstance(second_auth_state, dict):
            raise login_error("二次认证流程状态格式无效")

        self.session.headers.clear()
        self.session.headers.update(headers)
        self.session.cookies.clear()
        for item in cookies:
            if not isinstance(item, dict) or "name" not in item or "value" not in item:
                raise login_error("二次认证 Cookie 状态格式无效")
            self.session.cookies.set(
                item["name"],
                item["value"],
                domain=item.get("domain"),
                path=item.get("path", "/"),
                secure=bool(item.get("secure", False)),
                expires=item.get("expires"),
                rest=item.get("rest") or {},
            )
        self.second_auth.state = dict(second_auth_state) if second_auth_state else None
        return self

    def get_second_auth(self):
        return self.second_auth.describe()

    def send_sms_code(self):
        return self.second_auth.method("sms").send_code()

    def verify_sms_code(self, code):
        return self.second_auth.method("sms").verify_code(code)

    def begin_dingtalk_qr(self):
        return self.second_auth.method("dingtalk_qr").begin()

    def poll_dingtalk_qr(self):
        return self.second_auth.method("dingtalk_qr").poll()

    def _complete_second_auth(self, data, state):
        response = self.session.post(
            self.base_url,
            data=data,
            headers={"Referer": state["page_url"]},
            allow_redirects=False,
        )
        callback = response.headers.get("Location", "")
        if response.status_code not in (301, 302, 303, 307, 308) or not callback:
            error_code = self._get_page_value(response.text, "login-error-code")
            raise login_error("二次认证登录失败: {}".format(error_code or response.status_code))
        return self._result(callback)

    def _login_form(self, username, password, callback_url):
        params = {"service": callback_url} if callback_url else None
        page = self.session.get(self.base_url, params=params)
        if page.status_code != 200:
            raise login_error("登录页面获取失败: {}".format(page.status_code))

        execution = self._get_page_value(page.text, "login-page-flowkey")
        crypto_key = self._get_page_value(page.text, "login-croypto")
        if not execution or not crypto_key:
            raise login_error("无法解析统一身份认证登录参数")

        data = {
            "username": username,
            "password": self._encrypt(password, crypto_key),
            "execution": execution,
            "croypto": crypto_key,
            "captcha_payload": self._encrypt("{}", crypto_key),
            "_eventId": "submit",
            "type": "UsernamePassword",
            "geolocation": "",
            "captcha_code": "",
            "risk_payload": self._get_risk_payload(),
            "targetSystem": "sso",
            "siteId": "sourceId",
            "riskEngine": "USTC",
        }
        response = self.session.post(
            self.base_url,
            data=data,
            headers={
                "Content-Type": CONFIG["common"]["content_type_form"],
                "Origin": self.sso_origin,
                "Referer": page.url,
            },
            allow_redirects=False,
        )
        callback = response.headers.get("Location", "")
        if response.status_code in (301, 302, 303, 307, 308) and callback:
            return callback

        error_code = self._get_page_value(response.text, "login-error-code")
        if error_code:
            raise login_error("登录失败: 统一身份认证错误 {}".format(error_code))
        if self._get_page_value(response.text, "sso-second") == "true":
            phone = self._get_page_value(response.text, "phone-number")
            second_crypto = self._get_page_value(response.text, "login-croypto")
            self.second_auth.start({
                "execution": self._get_page_value(response.text, "login-page-flowkey"),
                "crypto_key": second_crypto or crypto_key,
                "phone": phone,
                "masked_phone": phone[:3] + "****" + phone[-4:] if len(phone) >= 7 else "",
                "page_url": response.url,
                "target_system": self._get_page_value(response.text, "targetSystem"),
                "site_id": self._get_page_value(response.text, "siteId"),
            })
            raise second_auth_required("密码验证成功，需要短信或钉钉扫码二次认证")
        if response.status_code in (401, 403):
            raise login_error("登录失败: 账号、密码或安全验证未通过")
        raise login_error("登录失败: HTTP {}".format(response.status_code))


    def login(self, username: str, password: str, callback_url: str = "", webvpn_mode=False,retries=0) -> Dict[str, Any]:
        try:
            callback = self._login_form(username, password, callback_url)

            return self._result(callback)
        except login_error:
            raise
        except requests.RequestException as e:
            raise login_error(e)
        except Exception as e:
            raise login_error(str(e))
