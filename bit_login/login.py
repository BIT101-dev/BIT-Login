from typing import Any, Callable, Dict, Optional
from urllib.parse import parse_qs, urljoin, urlparse

import requests

from .config import CONFIG
from .sso import BitSsoClient, DdddOcrCaptchaSolver
from .sso.exceptions import BitSsoError
from .sso.models import SmsCodeContext


class login_error(Exception):
    """BIT login compatibility error."""


SmsCodeCallback = Callable[[SmsCodeContext], str]


class login:
    """Compatibility wrapper backed by the current CAS browser flow."""

    def __init__(
        self,
        base_url: str = "",
        *,
        session: Optional[requests.Session] = None,
        captcha_solver: Optional[Callable[..., str]] = None,
        sms_code_callback: Optional[SmsCodeCallback] = None,
        timeout: float = 25.0,
    ) -> None:
        if base_url and "/cas/v1/tickets" not in base_url:
            sso_base_url = base_url.rstrip("/")
        else:
            sso_base_url = CONFIG["urls"]["base"].get(
                "sso_base", "https://sso.bit.edu.cn"
            )
        self.session = session or requests.Session()
        self.sms_code_callback = sms_code_callback
        self.captcha_solver = captcha_solver or self._default_captcha_solver()
        self._client = BitSsoClient(
            session=self.session,
            base_url=sso_base_url,
            captcha_solver=self.captcha_solver,
            timeout=timeout,
        )

    @staticmethod
    def _default_captcha_solver() -> Optional[Callable[..., str]]:
        try:
            return DdddOcrCaptchaSolver()
        except BitSsoError:
            return None

    def login(
        self,
        username: str,
        password: str,
        callback_url: str = "",
        webvpn_mode: bool = False,
        retries: int = 0,
        *,
        sms_code_callback: Optional[SmsCodeCallback] = None,
        trust_device: bool = False,
    ) -> Dict[str, Any]:
        del webvpn_mode, retries
        if not callback_url:
            raise login_error("callback_url must not be empty")
        try:
            result = self._client.login_password(
                username,
                password,
                service=callback_url,
                sms_code_callback=sms_code_callback or self.sms_code_callback,
                trust_device=trust_device,
                follow_redirects=False,
            )
        except (BitSsoError, requests.RequestException, ValueError) as exc:
            raise login_error(str(exc)) from exc

        callback = self._ticket_callback(result.response, result.ticket, callback_url)
        cookies = self.session.cookies.get_dict()
        return {
            "cookie_json": cookies,
            "cookie": "; ".join(f"{key}={value}" for key, value in cookies.items()),
            "callback": callback,
            "ticket": result.ticket,
        }

    @staticmethod
    def _ticket_callback(response: Any, ticket: Optional[str], service: str) -> str:
        headers = getattr(response, "headers", {}) or {}
        location = headers.get("Location")
        response_url = str(getattr(response, "url", "") or "")
        if location:
            callback = urljoin(response_url, str(location))
        elif ticket:
            separator = "&" if "?" in service else "?"
            callback = f"{service}{separator}ticket={ticket}"
        else:
            callback = response_url

        callback_ticket = next(
            iter(parse_qs(urlparse(callback).query).get("ticket", [])), None
        )
        if not callback_ticket and not ticket:
            raise login_error("CAS did not issue a service ticket")
        return callback
