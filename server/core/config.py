import os


ALLOWED_ORIGINS = [
    "https://bit101.cn",
    "http://bit101.cn",
    "http://127.0.0.1:3000",
    "http://localhost:3000",
    "https://deploy-preview-57--bit101-demo.netlify.app",
    "http://deploy-preview-57--bit101-demo.netlify.app",
]

BASE_URL = os.getenv("BASE_URL", "https://login.bit101.flwfdd.xyz")
HTTP_CONNECT_TIMEOUT = float(os.getenv("HTTP_CONNECT_TIMEOUT", "5"))
HTTP_READ_TIMEOUT = float(os.getenv("HTTP_READ_TIMEOUT", "25"))
HTTP_TIMEOUT = (HTTP_CONNECT_TIMEOUT, HTTP_READ_TIMEOUT)

MFA_METHOD_NAMES = {
    "sms": "sms",
    "dingtalk": "dingtalk_qr",
}
_configured_mfa_methods = [
    method.strip().lower()
    for method in os.getenv("MFA_METHODS", "sms,dingtalk").split(",")
    if method.strip()
]
_invalid_mfa_methods = set(_configured_mfa_methods) - set(MFA_METHOD_NAMES)
if _invalid_mfa_methods:
    raise ValueError(
        "Unsupported MFA_METHODS value(s): {}".format(
            ", ".join(sorted(_invalid_mfa_methods))
        )
    )
if not _configured_mfa_methods:
    raise ValueError("MFA_METHODS must enable at least one method")
ENABLED_MFA_METHODS = tuple(
    method for method in MFA_METHOD_NAMES if method in _configured_mfa_methods
)
