from typing import Optional


class BitSsoError(Exception):
    """Base error raised by this package."""


class ConfigurationError(BitSsoError):
    """The SSO page did not contain the required login configuration."""


class CaptchaError(BitSsoError):
    """A captcha could not be obtained or recognized."""


class SmsVerificationError(BitSsoError):
    """The SMS code was rejected before login submission."""


class LoginError(BitSsoError):
    """CAS returned the login page with an error."""

    def __init__(
        self,
        message: str,
        *,
        code: str = "",
        final_url: str = "",
        status_code: Optional[int] = None,
        redirect_count: int = 0,
        ticket_issued: bool = False,
        risk_mode: str = "",
        flow_replaced: Optional[bool] = None,
        captcha_required: bool = False,
    ) -> None:
        display_message = f"{message} (code: {code})" if code else message
        diagnostics = []
        if status_code is not None:
            diagnostics.append(f"status={status_code}")
        diagnostics.append(f"redirects={redirect_count}")
        diagnostics.append(f"risk={risk_mode or 'unknown'}")
        diagnostics.append(f"ticket={'yes' if ticket_issued else 'no'}")
        flow_state = "unknown" if flow_replaced is None else (
            "replaced" if flow_replaced else "same"
        )
        diagnostics.append(f"flow={flow_state}")
        diagnostics.append(f"captcha={'yes' if captcha_required else 'no'}")
        display_message += " [" + ", ".join(diagnostics) + "]"
        super().__init__(display_message)
        self.code = code
        self.final_url = final_url
        self.status_code = status_code
        self.redirect_count = redirect_count
        self.ticket_issued = ticket_issued
        self.risk_mode = risk_mode
        self.flow_replaced = flow_replaced
        self.captcha_required = captcha_required
