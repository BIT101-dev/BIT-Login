import base64
import hashlib
import json
import re
import time
from html.parser import HTMLParser
from typing import Any, Callable, Mapping, Optional, Union
from urllib.parse import parse_qs, quote, urlencode, urljoin, urlparse

import requests

from .crypto import (
    decrypt_url_crypto_response,
    encrypt_aes_base64,
    encrypt_url_crypto_body,
    protected_csrf_headers,
)
from .exceptions import (
    CaptchaError,
    ConfigurationError,
    LoginError,
    SmsVerificationError,
)
from .fingerprint import BrowserFingerprintProfile
from .models import (
    CaptchaContext,
    LoginPage,
    LoginResult,
    RiskContext,
    SecondFactorPage,
    SmsCodeContext,
)

CaptchaSolver = Callable[[bytes, CaptchaContext], str]
SmsCodeCallback = Callable[[SmsCodeContext], str]
RiskTokenProvider = Callable[[RiskContext], Mapping[str, Any]]

_URL_CRYPTO_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjVr1zKwohU3xA0afprWLSQvIymaSH/V27MedFc+CecXSnORIFMAp4uEIb4taDq/2X4eMeTI66Mu/rB5GKSFDbExF2Gu4NaO/CNDpf1gHMScUrIFCh4CDqzBnx17kclvezLkIK0T8FVa4cRsINvzjbnA6jUSMaf6Fm1n9wTAtW6QYBjssGOEtCj+c38PTBdFMmJbXp3brt1tEBesz6lb3Fjp76FGvDZ08xtYG8fxYPuiMwKU04eS+mcX/BunwgpU3zwekHYB+PWRIvq0lBry9Wms25sJE5T/RAv5fEuMLbBkfcZK3+7ivSZthTmPpr2Ap/ji70ZZ6u2jvR5VJq+LJHQIDAQAB
-----END PUBLIC KEY-----"""

_LOGIN_ERROR_MESSAGES = {
    "1030027": "username or password is incorrect",
    "1030028": "the account is locked",
    "1030031": "username or password is incorrect",
    "1320007": "the verification code is incorrect",
    "1320010": "the image captcha is incorrect",
    "1330001": "the login was rejected by account risk control",
    "1410040": "the account is invalid",
    "1410041": "the account is invalid",
    "3910001": "the account is dormant and must be reactivated",
}


class _LoginHtmlParser(HTMLParser):
    WANTED_IDS = {
        "login-page-flowkey",
        "login-croypto",
        "recaptchaVendor",
        "riskSystemSwitch",
        "targetSystem",
        "siteId",
        "login-error-msg",
        "login-error-code",
        "user-object-id",
        "second-auth-user-id",
        "second-auth-tip",
        "phone-number",
    }

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.values: dict[str, str] = {}
        self.form_action = ""
        self._capture_id: Optional[str] = None
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        values = dict(attrs)
        element_id = values.get("id")
        if element_id in self.WANTED_IDS:
            self._capture_id = element_id
            self._parts = []
        if tag.lower() == "form" and not self.form_action:
            self.form_action = values.get("action") or ""

    def handle_data(self, data: str) -> None:
        if self._capture_id is not None:
            self._parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if self._capture_id is not None and tag.lower() in {"p", "div", "span"}:
            self.values[self._capture_id] = "".join(self._parts).strip()
            self._capture_id = None
            self._parts = []


def _parse_login_html(html: str, response_url: str) -> tuple[Optional[LoginPage], str, str]:
    parser = _LoginHtmlParser()
    parser.feed(html)
    execution = parser.values.get("login-page-flowkey", "")
    crypto_key = parser.values.get("login-croypto", "")
    page = None
    if execution and crypto_key:
        # Angular renders action="login"; unlike an omitted action, this drops the
        # initial page's service query because execution already owns that flow.
        form_action = parser.form_action or "login"
        page = LoginPage(
            execution=execution,
            crypto_key=crypto_key,
            form_action=urljoin(response_url, form_action),
            recaptcha_vendor=parser.values.get("recaptchaVendor", ""),
            risk_system=parser.values.get("riskSystemSwitch", ""),
            target_system=parser.values.get("targetSystem", ""),
            site_id=parser.values.get("siteId", ""),
        )
    return (
        page,
        parser.values.get("login-error-msg", ""),
        parser.values.get("login-error-code", ""),
    )


def _parse_second_factor_html(
    html: str, response_url: str
) -> Optional[SecondFactorPage]:
    parser = _LoginHtmlParser()
    parser.feed(html)
    execution = parser.values.get("login-page-flowkey", "")
    user_object_id = parser.values.get("user-object-id", "")
    second_auth_user_id = parser.values.get("second-auth-user-id", "")
    has_gateway_marker = any(
        marker in html
        for marker in ("secondSmsLoginForm", "second-auth-tip", "cas-gateway")
    )
    if not execution or not user_object_id or not has_gateway_marker:
        return None
    return SecondFactorPage(
        execution=execution,
        form_action=urljoin(response_url, parser.form_action or "login"),
        user_object_id=user_object_id,
        user_id=second_auth_user_id,
        phone=parser.values.get("phone-number", ""),
    )


def default_sms_code_callback(context: SmsCodeContext) -> str:
    target = context.masked_phone or context.phone
    return input(f"请输入发送到 {target} 的短信验证码: ").strip()


class BitSsoClient:
    """A stateful client that mirrors the BIT CAS browser login requests."""

    DEFAULT_BASE_URL = "https://sso.bit.edu.cn"
    PHONE_PATTERN = re.compile(r"^1[0-9]{10}$")

    def __init__(
        self,
        *,
        captcha_solver: Optional[CaptchaSolver] = None,
        risk_token_provider: Optional[RiskTokenProvider] = None,
        fingerprint_profile: Optional[BrowserFingerprintProfile] = None,
        session: Optional[requests.Session] = None,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = 15.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.cas_url = self.base_url + "/cas"
        self.gate_url = self.base_url + "/gate"
        self.session = session or requests.Session()
        self.captcha_solver = captcha_solver
        self.risk_token_provider = risk_token_provider
        self.fingerprint_profile = fingerprint_profile or BrowserFingerprintProfile()
        self.timeout = timeout
        self.last_risk_mode = "not-required"
        self.last_captcha_required = False
        self._login_referer = f"{self.cas_url}/login"
        self._last_execution = ""
        default_headers = getattr(self.session, "headers", None)
        if default_headers is not None:
            default_headers.update(
                {
                    "User-Agent": (
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        "AppleWebKit/537.36 (KHTML, like Gecko) "
                        "Chrome/150.0.0.0 Safari/537.36"
                    ),
                    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.7",
                    "sec-ch-ua": (
                        '"Not;A=Brand";v="8", "Chromium";v="150", '
                        '"Google Chrome";v="150"'
                    ),
                    "sec-ch-ua-mobile": "?0",
                    "sec-ch-ua-platform": '"macOS"',
                }
            )

    def login_password(
        self,
        username: str,
        password: str,
        *,
        service: Optional[str] = None,
        captcha_solver: Optional[CaptchaSolver] = None,
        sms_code_callback: Optional[SmsCodeCallback] = None,
        trust_device: bool = False,
        follow_redirects: bool = True,
    ) -> LoginResult:
        if not username or not password:
            raise ValueError("username and password must not be empty")
        username = username.strip()
        if not username:
            raise ValueError("username must not contain only whitespace")
        self.last_risk_mode = "not-required"
        self.last_captcha_required = False
        loaded = self._load_login_page(service, follow_redirects=follow_redirects)
        if isinstance(loaded, LoginResult):
            return loaded
        page = loaded
        captcha_code = ""
        captcha_payload: Mapping[str, Any] = {}
        captcha_info = self._request_json(
            "GET",
            f"{self.cas_url}/api/protected/user/findCaptchaCount/{quote(username, safe='')}",
        )
        captcha_data = self._response_data(captcha_info)
        if isinstance(captcha_data, Mapping) and captcha_data.get("captchaInvisible"):
            self.last_captcha_required = True
            captcha_url = str(captcha_data.get("captchaUrl") or "")
            if not captcha_url:
                raise CaptchaError("the server required a captcha but supplied no image URL")
            solver = captcha_solver or self.captcha_solver
            captcha_code = self._solve_captcha(
                self._captcha_image(captcha_url),
                CaptchaContext(purpose="password", username=username),
                solver,
            )
            payload = captcha_data.get("captchaPayload")
            if isinstance(payload, Mapping):
                captcha_payload = payload

        form = self._common_form(page, "UsernamePassword", username)
        form.update(
            {
                "captcha_code": captcha_code,
                "password": encrypt_aes_base64(password, page.crypto_key),
                "captcha_payload": encrypt_aes_base64(
                    self._compact_json(captcha_payload), page.crypto_key
                ),
            }
        )
        self._add_risk_fields(form, page, "UsernamePassword", username)
        response = self._post_login(
            page, form, service, follow_redirects=follow_redirects
        )
        second_factor = self._parse_second_factor(response)
        if second_factor is not None:
            return self._complete_password_sms_factor(
                username,
                second_factor,
                sms_code_callback or default_sms_code_callback,
                trust_device=trust_device,
                follow_redirects=follow_redirects,
            )
        return self._login_result(response)

    def login_sms(
        self,
        phone: str,
        *,
        service: Optional[str] = None,
        sms_code_callback: Optional[SmsCodeCallback] = None,
        captcha_solver: Optional[CaptchaSolver] = None,
    ) -> LoginResult:
        if not self.PHONE_PATTERN.fullmatch(phone):
            raise ValueError("phone must be an 11-digit mainland China mobile number")
        self.last_risk_mode = "not-required"
        self.last_captcha_required = True
        page = self._load_login_page(service)
        solver = captcha_solver or self.captcha_solver
        captcha_image = self._request_bytes(
            "POST",
            f"{self.gate_url}/sso-extend/protected/api/aggregate/sms/"
            "publicNoToken/generate",
            json={"phone": phone, "type": "DEFAULT"},
        )
        captcha_code = self._solve_captcha(
            captcha_image,
            CaptchaContext(purpose="sms", phone=phone),
            solver,
        )
        encoded_code = quote(captcha_code, safe="")
        encoded_phone = quote(phone, safe="")
        send_result = self._request_json(
            "GET",
            f"{self.gate_url}/sso-extend/protected/api/aggregate/sms/"
            f"publicNoToken/sendCheckCaptcha/DEFAULT/{encoded_code}/{encoded_phone}/0008",
        )
        send_code = self._response_code(send_result)
        if (
            send_code is not None
            and send_code != 200
            and not self._sms_code_remains_valid(send_result)
        ):
            raise SmsVerificationError(
                self._response_message(send_result) or "failed to trigger the SMS code"
            )

        callback = sms_code_callback or default_sms_code_callback
        masked_phone = f"{phone[:3]}****{phone[-4:]}"
        sms_code = str(callback(SmsCodeContext(phone, masked_phone))).strip()
        if not sms_code:
            raise SmsVerificationError("the SMS callback returned an empty code")

        check = self._request_json(
            "POST",
            f"{self.cas_url}/api/protected/sms/checkTokenResult",
            json={"phone": phone, "token": sms_code, "delete": False},
        )
        if self._response_code(check) != 200:
            raise SmsVerificationError(self._response_message(check) or "SMS code was rejected")

        form = self._common_form(page, "smsLogin", phone)
        form.update({"captcha_code": "", "password": sms_code})
        self._add_risk_fields(form, page, "smsLogin", phone)
        try:
            response = self._post_login(page, form, service)
        except requests.HTTPError as exc:
            if self._is_sms_rejection(exc):
                raise SmsVerificationError(
                    "短信验证码错误或已失效，请重新发起登录"
                ) from exc
            raise
        return self._login_result(response)

    def _load_login_page(
        self, service: Optional[str], *, follow_redirects: bool = True
    ) -> Union[LoginPage, LoginResult]:
        response = self._request(
            "GET",
            f"{self.cas_url}/login",
            params=self._service_params(service),
            cache_bust=False,
            allow_redirects=follow_redirects,
        )
        page, _, _ = _parse_login_html(response.text, response.url)
        if page is None:
            if self._find_ticket(
                response, tuple(getattr(response, "history", ()) or ())
            ):
                return self._login_result(response)
            raise ConfigurationError("CAS login page is missing execution or croypto")
        self._login_referer = response.url
        self._last_execution = page.execution
        return page

    def _common_form(self, page: LoginPage, login_type: str, username: str) -> dict[str, str]:
        return {
            "type": login_type,
            "_eventId": "submit",
            "geolocation": "",
            "execution": page.execution,
            "username": username,
            "croypto": page.crypto_key,
        }

    def _add_risk_fields(
        self, form: dict[str, str], page: LoginPage, login_type: str, username: str
    ) -> None:
        if page.risk_system.upper() != "USTC":
            return
        context = RiskContext(login_type, username, page.target_system, page.site_id)
        if self.risk_token_provider is not None:
            payload = self.risk_token_provider(context)
            self.last_risk_mode = "custom"
        else:
            payload = self._default_ustc_risk_payload()
        form.update(
            {
                "risk_payload": encrypt_aes_base64(
                    self._compact_json(payload), page.crypto_key
                ),
                "targetSystem": page.target_system or "sso",
                "siteId": page.site_id or "sourceId",
                "riskEngine": "true",
            }
        )

    def _default_ustc_risk_payload(self) -> Mapping[str, Any]:
        device_cookie = self._ensure_device_cookie()
        user_agent = ""
        session_headers = getattr(self.session, "headers", None)
        if session_headers is not None:
            user_agent = str(session_headers.get("User-Agent") or "")
        fingerprint = self.fingerprint_profile.build(
            cookie_value=device_cookie,
            user_agent=user_agent,
            group_id=self._cookie_value("riskSystemGroupId"),
        )
        try:
            result = self._request_json(
                "POST",
                f"{self.base_url}/ustc-rba-front/fp",
                headers={"Origin": self.base_url},
                json=fingerprint,
            )
            token = self._risk_response_token(result)
            if not token:
                raise ConfigurationError("USTC risk response did not contain responsetoken")
        except (requests.RequestException, ConfigurationError):
            self.last_risk_mode = "error-fallback"
            return {"error": True}

        self.last_risk_mode = "ustc-token"
        return {
            "token": token,
            "groupId": self._cookie_value("riskSystemGroupId"),
        }

    @staticmethod
    def _parse_second_factor(response: Any) -> Optional[SecondFactorPage]:
        return _parse_second_factor_html(response.text, response.url)

    def _complete_password_sms_factor(
        self,
        username: str,
        page: SecondFactorPage,
        callback: SmsCodeCallback,
        *,
        trust_device: bool,
        follow_redirects: bool,
    ) -> LoginResult:
        self._login_referer = f"{self.cas_url}/"
        phone_data = self._second_factor_phone(page)
        opaque_phone = self._extract_string(phone_data, "tel") or page.phone
        masked_phone = self._extract_string(phone_data, "maskTel")
        if not opaque_phone:
            raise SmsVerificationError(
                "the second-factor page did not provide a bound phone identifier"
            )

        send_result = self._request_json(
            "POST",
            f"{self.cas_url}/api/protected/sms/publicNoToken/sendSmsCode",
            json={"phone": opaque_phone, "businessNo": "0008"},
        )
        if (
            self._response_code(send_result) != 200
            and not self._sms_code_remains_valid(send_result)
        ):
            raise SmsVerificationError(
                self._response_message(send_result)
                or "failed to send the second-factor SMS code"
            )

        code = str(
            callback(
                SmsCodeContext(
                    phone="",
                    masked_phone=masked_phone or "绑定手机",
                    purpose="password_second_factor",
                )
            )
        ).strip()
        if not code:
            raise SmsVerificationError("the SMS callback returned an empty code")

        check = self._request_json(
            "POST",
            f"{self.cas_url}/api/protected/sms/checkToken",
            json={
                "phone": opaque_phone,
                "token": code,
                "delete": False,
                "trustDevice": bool(trust_device),
            },
        )
        if self._response_code(check) != 200:
            raise SmsVerificationError(
                self._response_message(check) or "the second-factor SMS code was rejected"
            )

        form = {
            "username": username,
            "password": code,
            "type": "smsLogin",
            "_eventId": "submit",
            "geolocation": "",
            "execution": page.execution,
            "captcha_code": "",
            "trustDevice": str(bool(trust_device)).lower(),
        }
        try:
            response = self._request(
                "POST",
                page.form_action,
                data=form,
                allow_redirects=follow_redirects,
            )
        except requests.HTTPError as exc:
            if self._is_sms_rejection(exc):
                raise SmsVerificationError(
                    "短信验证码错误或已失效，请重新发起登录"
                ) from exc
            raise
        return self._login_result(response)

    def _second_factor_phone(self, page: SecondFactorPage) -> Any:
        body, encrypted_key, aes_key = encrypt_url_crypto_body(
            {"userId": page.user_object_id}, _URL_CRYPTO_PUBLIC_KEY
        )
        response = self._request(
            "POST",
            f"{self.cas_url}/api/protected/sms/getPhoneNumberByUserId",
            headers={
                "Content-Type": "application/json",
                "hasCrypto": "true",
                "privateKey": encrypted_key,
            },
            data=body,
        )
        if not response.text:
            raise ConfigurationError(
                "the second-factor phone endpoint returned an empty encrypted response"
            )
        unpacked = decrypt_url_crypto_response(response.text, aes_key)
        return self._response_data(unpacked) if isinstance(unpacked, Mapping) else unpacked

    @staticmethod
    def _extract_string(value: Any, key: str) -> str:
        if isinstance(value, Mapping) and value.get(key):
            return str(value[key])
        return ""

    def _post_login(
        self,
        page: LoginPage,
        form: Mapping[str, str],
        service: Optional[str],
        *,
        follow_redirects: bool = True,
    ) -> Any:
        del service
        return self._request(
            "POST", page.form_action, data=form, allow_redirects=follow_redirects
        )

    def _login_result(self, response: Any) -> LoginResult:
        page, error_message, error_code = _parse_login_html(response.text, response.url)
        second_factor_page = _parse_second_factor_html(response.text, response.url)
        final_url = response.url
        history = tuple(getattr(response, "history", ()) or ())
        ticket = self._find_ticket(response, history)
        login_markup = any(
            marker in response.text
            for marker in (
                "login-page-flowkey",
                "normalLoginForm",
                "smsLoginForm",
                "secondSmsLoginForm",
                "cas-gateway",
            )
        )
        is_login_url = urlparse(final_url).path.rstrip("/").endswith("/cas/login")
        if page is not None or second_factor_page is not None or (
            is_login_url and login_markup
        ):
            returned_execution = (
                page.execution
                if page is not None
                else second_factor_page.execution
                if second_factor_page is not None
                else ""
            )
            flow_replaced = (
                returned_execution != self._last_execution
                if returned_execution and self._last_execution
                else None
            )
            message, error_code = self._format_login_error(
                error_message, error_code, ticket_issued=bool(ticket)
            )
            raise LoginError(
                message,
                code=error_code,
                final_url=final_url,
                status_code=getattr(response, "status_code", None),
                redirect_count=len(history),
                ticket_issued=bool(ticket),
                risk_mode=self.last_risk_mode,
                flow_replaced=flow_replaced,
                captcha_required=self.last_captcha_required,
            )
        cookies = getattr(self.session, "cookies", {})
        cookie_values = cookies.get_dict() if hasattr(cookies, "get_dict") else dict(cookies)
        return LoginResult(response, final_url, ticket, cookie_values)

    def _format_login_error(
        self, message: str, code: str, *, ticket_issued: bool
    ) -> tuple[str, str]:
        message = message.strip()
        code = code.strip()
        if not code and message.isdigit():
            code, message = message, ""
        if message:
            return message, code
        if code:
            return _LOGIN_ERROR_MESSAGES.get(code, "CAS login failed"), code
        if ticket_issued:
            return (
                "CAS issued a service ticket, but the service redirected back to the login page",
                "",
            )
        if self.last_risk_mode == "error-fallback":
            return (
                "CAS returned the login page without an error code after USTC risk-token "
                "acquisition failed",
                "",
            )
        return (
            "CAS returned the login page without an error code; the execution may have "
            "expired or risk control may have rejected the request",
            "",
        )

    @staticmethod
    def _find_ticket(response: Any, history: tuple[Any, ...]) -> Optional[str]:
        candidates = list(history) + [response]
        for item in candidates:
            urls = [str(getattr(item, "url", "") or "")]
            headers = getattr(item, "headers", None)
            if headers is not None:
                location = headers.get("Location")
                if location:
                    urls.append(urljoin(urls[0], str(location)))
            for url in urls:
                query = parse_qs(urlparse(url).query)
                ticket = next(iter(query.get("ticket", [])), None)
                if ticket:
                    return ticket
        return None

    @staticmethod
    def _risk_response_token(value: Any) -> str:
        if not isinstance(value, Mapping):
            return ""
        token = value.get("responsetoken")
        if token:
            return str(token)
        data = value.get("data")
        if isinstance(data, Mapping) and data.get("responsetoken"):
            return str(data["responsetoken"])
        return ""

    def _cookie_value(self, name: str) -> str:
        cookies = getattr(self.session, "cookies", None)
        if cookies is None:
            return ""
        try:
            return str(cookies.get(name) or "")
        except (AttributeError, KeyError, TypeError):
            return ""

    def _ensure_device_cookie(self) -> str:
        existing = self._cookie_value("device")
        if existing:
            return existing
        timestamp = str(int(time.time() * 1000))
        value = hashlib.sha256(timestamp.encode("ascii")).hexdigest()
        cookies = getattr(self.session, "cookies", None)
        if cookies is not None:
            try:
                cookies.set(
                    "device",
                    value,
                    domain=urlparse(self.base_url).hostname,
                    path="/",
                )
            except AttributeError:
                cookies["device"] = value
        return value

    def _solve_captcha(
        self, image: bytes, context: CaptchaContext, solver: Optional[CaptchaSolver]
    ) -> str:
        if solver is None:
            raise CaptchaError("captcha is required, but no captcha_solver was configured")
        code = str(solver(image, context)).strip()
        if not code:
            raise CaptchaError("captcha_solver returned an empty value")
        return code

    def _request_json(self, method: str, url: str, **kwargs: Any) -> Any:
        response = self._request(method, url, **kwargs)
        try:
            return response.json()
        except (ValueError, TypeError) as exc:
            raise ConfigurationError(f"expected JSON from {url}") from exc

    def _request_bytes(self, method: str, url: str, **kwargs: Any) -> bytes:
        return bytes(self._request(method, url, **kwargs).content)

    def _captcha_image(self, value: str) -> bytes:
        if value.startswith("data:"):
            try:
                metadata, encoded = value.split(",", 1)
                if ";base64" not in metadata:
                    raise ValueError("captcha data URL is not Base64 encoded")
                return base64.b64decode(encoded, validate=True)
            except (ValueError, TypeError) as exc:
                raise CaptchaError("the server supplied an invalid captcha data URL") from exc
        return self._request_bytes("GET", urljoin(self.cas_url + "/", value))

    def _request(self, method: str, url: str, **kwargs: Any) -> Any:
        method = method.upper()
        cache_bust = bool(kwargs.pop("cache_bust", True))
        headers = dict(kwargs.pop("headers", {}) or {})
        headers.setdefault("Referer", self._login_referer)
        if method not in {"GET", "HEAD", "OPTIONS"}:
            headers.setdefault("Origin", self.base_url)
        if "protected" in url:
            headers.update(protected_csrf_headers())
            headers.setdefault("Sid-Language", "zh_CN")
        if method == "GET" and cache_bust:
            params = dict(kwargs.pop("params", {}) or {})
            if params:
                separator = "&" if urlparse(url).query else "?"
                url += separator + urlencode(params, doseq=True)
            separator = "&" if urlparse(url).query else "?"
            url += separator + str(int(time.time() * 1000))
        elif kwargs.get("params") is None:
            kwargs.pop("params", None)
        response = self.session.request(
            method, url, headers=headers, timeout=self.timeout, **kwargs
        )
        response.raise_for_status()
        return response

    @staticmethod
    def _service_params(service: Optional[str]) -> Optional[dict[str, str]]:
        return {"service": service} if service else None

    @staticmethod
    def _compact_json(value: Mapping[str, Any]) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _response_data(value: Any) -> Any:
        return value.get("data") if isinstance(value, Mapping) else None

    @staticmethod
    def _response_code(value: Any) -> Any:
        return value.get("code") if isinstance(value, Mapping) else None

    @staticmethod
    def _response_message(value: Any) -> str:
        if not isinstance(value, Mapping):
            return ""
        for key in ("message", "msg", "errorMessage"):
            if value.get(key):
                return str(value[key])
        data = value.get("data")
        if isinstance(data, Mapping):
            for key in ("message", "msg", "errorMessage"):
                if data.get(key):
                    return str(data[key])
        return ""

    @classmethod
    def _sms_code_remains_valid(cls, value: Any) -> bool:
        message = cls._response_message(value)
        return "验证码" in message and "有效期内" in message and "重复发送" in message

    @staticmethod
    def _is_sms_rejection(error: requests.HTTPError) -> bool:
        response = error.response
        return response is not None and response.status_code in {400, 401, 403}
