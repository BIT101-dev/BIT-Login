from typing import Optional

from .exceptions import CaptchaError
from .models import CaptchaContext


class DdddOcrCaptchaSolver:
    """Lazy ddddocr adapter, so importing bit_sso keeps it optional."""

    def __init__(self, ocr: Optional[object] = None, **ocr_options: object) -> None:
        if ocr is None:
            try:
                import ddddocr
            except ImportError as exc:
                raise CaptchaError(
                    "ddddocr is not installed; run: pip install 'bit-sso[captcha]'"
                ) from exc
            ocr_options.setdefault("show_ad", False)
            ocr = ddddocr.DdddOcr(**ocr_options)
        self._ocr = ocr

    def __call__(self, image: bytes, context: CaptchaContext) -> str:
        del context
        result = self._ocr.classification(image)
        code = str(result).strip()
        if not code:
            raise CaptchaError("ddddocr returned an empty captcha result")
        return code
