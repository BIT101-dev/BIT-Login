import base64
import json
import os
import time
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


class RegistrationTokenError(Exception):
    """A registration token cannot be issued."""


class RegistrationAudienceError(RegistrationTokenError):
    """The requested registration token audience is not allowed."""


def _base64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _json_part(value):
    return _base64url(
        json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    )


def issue_registration_token(subject, challenge_id, audience):
    key_file = os.getenv("REGISTRATION_JWT_PRIVATE_KEY_FILE", "").strip()
    if not key_file:
        raise RegistrationTokenError(
            "REGISTRATION_JWT_PRIVATE_KEY_FILE is not configured"
        )
    try:
        key_data = Path(key_file).expanduser().read_bytes()
        private_key = serialization.load_pem_private_key(
            key_data, password=None
        )
    except (OSError, TypeError, ValueError) as exc:
        raise RegistrationTokenError(
            "registration JWT private key is invalid"
        ) from exc
    if not isinstance(private_key, Ed25519PrivateKey):
        raise RegistrationTokenError(
            "registration JWT private key must be an Ed25519 key"
        )
    try:
        ttl = int(os.getenv("REGISTRATION_JWT_TTL", "300"))
    except ValueError as exc:
        raise RegistrationTokenError(
            "REGISTRATION_JWT_TTL must be an integer"
        ) from exc
    if ttl < 1:
        raise RegistrationTokenError(
            "REGISTRATION_JWT_TTL must be positive"
        )

    allowed_audiences = {
        item.strip()
        for item in os.getenv(
            "REGISTRATION_JWT_ALLOWED_AUDIENCES", ""
        ).split(",")
        if item.strip()
    }
    if not allowed_audiences:
        raise RegistrationTokenError(
            "REGISTRATION_JWT_ALLOWED_AUDIENCES is not configured"
        )
    audience = str(audience).strip()
    if audience not in allowed_audiences:
        raise RegistrationAudienceError(
            "registration JWT audience is not allowed"
        )

    subject = str(subject).strip()
    if not subject:
        raise RegistrationTokenError("registration subject is empty")
    now = int(time.time())
    header = {
        "alg": "EdDSA",
        "kid": os.getenv(
            "REGISTRATION_JWT_KEY_ID", "registration-1"
        ),
        "typ": "JWT",
    }
    payload = {
        "aud": audience,
        "exp": now + ttl,
        "iat": now,
        "iss": os.getenv("REGISTRATION_JWT_ISSUER", "bit-login"),
        "jti": str(challenge_id),
        "purpose": "registration",
        "sub": subject,
    }
    signing_input = f"{_json_part(header)}.{_json_part(payload)}"
    signature = private_key.sign(signing_input.encode("ascii"))
    return f"{signing_input}.{_base64url(signature)}", ttl
