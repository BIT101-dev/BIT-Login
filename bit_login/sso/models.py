from dataclasses import dataclass
from typing import Any, Mapping, Optional


@dataclass(frozen=True)
class LoginPage:
    execution: str
    crypto_key: str
    form_action: str
    recaptcha_vendor: str = ""
    risk_system: str = ""
    target_system: str = ""
    site_id: str = ""


@dataclass(frozen=True)
class SecondFactorPage:
    execution: str
    form_action: str
    user_object_id: str
    user_id: str = ""
    phone: str = ""


@dataclass(frozen=True)
class CaptchaContext:
    purpose: str
    username: str = ""
    phone: str = ""


@dataclass(frozen=True)
class SmsCodeContext:
    phone: str
    masked_phone: str
    purpose: str = "sms_login"


@dataclass(frozen=True)
class RiskContext:
    login_type: str
    username: str
    target_system: str
    site_id: str


@dataclass(frozen=True)
class LoginResult:
    response: Any
    final_url: str
    ticket: Optional[str]
    cookies: Mapping[str, str]
