import base64
import binascii
import json
import os
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


TOKEN_VERSION = 1
TOKEN_AAD = b"bit-login:mfa:v1"


class ChallengeError(Exception):
    pass


class ChallengeConfigurationError(ChallengeError):
    pass


class InvalidChallenge(ChallengeError):
    pass


class ExpiredChallenge(ChallengeError):
    pass


def _urlsafe_decode(value):
    if not isinstance(value, str):
        raise InvalidChallenge("二次认证凭据格式无效")
    try:
        padding = "=" * (-len(value) % 4)
        return base64.urlsafe_b64decode((value + padding).encode("ascii"))
    except (TypeError, ValueError, UnicodeError, binascii.Error):
        raise InvalidChallenge("二次认证凭据格式无效")


def _load_key():
    encoded = os.getenv("MFA_STATE_KEY", "")
    if not encoded:
        raise ChallengeConfigurationError("服务端未配置 MFA_STATE_KEY")
    try:
        key = _urlsafe_decode(encoded)
    except InvalidChallenge:
        raise ChallengeConfigurationError("MFA_STATE_KEY 不是有效的 URL-safe Base64 密钥")
    if len(key) != 32:
        raise ChallengeConfigurationError("MFA_STATE_KEY 必须是 32 字节 URL-safe Base64 密钥")
    return key


def challenge_ttl():
    try:
        ttl = int(os.getenv("MFA_STATE_TTL", "300"))
    except ValueError:
        raise ChallengeConfigurationError("MFA_STATE_TTL 必须是整数")
    if ttl <= 0:
        raise ChallengeConfigurationError("MFA_STATE_TTL 必须大于 0")
    return ttl


def issue_challenge(username, service, state, now=None):
    now = int(time.time() if now is None else now)
    payload = {
        "version": TOKEN_VERSION,
        "issued_at": now,
        "expires_at": now + challenge_ttl(),
        "username": username,
        "service": service,
        "state": state,
    }
    plaintext = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    nonce = os.urandom(12)
    ciphertext = AESGCM(_load_key()).encrypt(nonce, plaintext, TOKEN_AAD)
    return base64.urlsafe_b64encode(nonce + ciphertext).rstrip(b"=").decode("ascii")


def restore_challenge(token, expected_username=None, now=None):
    raw = _urlsafe_decode(token)
    if len(raw) < 29:
        raise InvalidChallenge("二次认证凭据格式无效")
    try:
        plaintext = AESGCM(_load_key()).decrypt(raw[:12], raw[12:], TOKEN_AAD)
        payload = json.loads(plaintext.decode("utf-8"))
    except ChallengeConfigurationError:
        raise
    except Exception:
        raise InvalidChallenge("二次认证凭据无效")

    if not isinstance(payload, dict) or payload.get("version") != TOKEN_VERSION:
        raise InvalidChallenge("不支持的二次认证凭据版本")
    required = ("issued_at", "expires_at", "username", "service", "state")
    if any(key not in payload for key in required) or not isinstance(payload["state"], dict):
        raise InvalidChallenge("二次认证凭据内容无效")
    current = int(time.time() if now is None else now)
    if current >= payload["expires_at"]:
        raise ExpiredChallenge("二次认证凭据已过期")
    if expected_username is not None and payload["username"] != expected_username:
        raise InvalidChallenge("二次认证凭据与用户不匹配")
    return payload
