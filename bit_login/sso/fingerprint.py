import hashlib
import json
from dataclasses import dataclass
from typing import Any, Sequence


def _js_json(value: Any) -> str:
    if value is None:
        return "undefined"
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class BrowserFingerprintProfile:
    """Non-secret browser characteristics used to create a fresh risk profile."""

    fonts: Sequence[str] = (
        "Arial",
        "Helvetica Neue",
        "PingFang SC",
        "Times New Roman",
    )
    device_memory: int = 16
    hardware_concurrency: int = 10
    timezone: str = "Asia/Shanghai"
    cpu_class: str = "not available"
    platform: str = "MacIntel"
    language: str = "zh-CN"
    screen_resolution: tuple[int, int] = (956, 1470)
    platform_authenticator: str = "support"

    def build(self, *, cookie_value: str, user_agent: str, group_id: str = "") -> dict[str, str]:
        values = {
            "fonts": _js_json(list(self.fonts)),
            "deviceMemory": _js_json(self.device_memory),
            "hardwareConcurrency": _js_json(self.hardware_concurrency),
            "timezone": _js_json(self.timezone),
            "cpuClass": _js_json(self.cpu_class),
            "platform": _js_json(self.platform),
            "language": _js_json(self.language),
            "screenResolution": _js_json(list(self.screen_resolution)),
        }
        combined = "".join(values.values())
        return {
            "fonts": _sha256(values["fonts"]),
            "deviceMemory": _sha256(values["deviceMemory"]),
            "hardwareConcurrency": _sha256(values["hardwareConcurrency"]),
            "localgroupId": group_id,
            "timezone": values["timezone"],
            "cpuClass": _sha256(values["cpuClass"]),
            "platform": values["platform"],
            "language": values["language"],
            "screenResolution": values["screenResolution"],
            "fingerprint": _sha256(combined),
            "cookieValue": cookie_value,
            "userAgent": user_agent,
            "platformAuthenticator": self.platform_authenticator,
        }
