import base64
import hashlib
import json
import secrets
import string

from Crypto.Cipher import AES
from Crypto.Cipher import PKCS1_v1_5
from Crypto.PublicKey import RSA
from Crypto.Util.Padding import pad, unpad


def encrypt_aes_base64(plaintext: str, encoded_key: str) -> str:
    """Match the page's AES-ECB/PKCS7/Base64 transformation."""
    try:
        key = base64.b64decode(encoded_key, validate=True)
    except (ValueError, TypeError) as exc:
        raise ValueError("croypto is not valid Base64") from exc
    if len(key) not in (16, 24, 32):
        raise ValueError("croypto must decode to a valid AES key")
    cipher = AES.new(key, AES.MODE_ECB)
    encrypted = cipher.encrypt(pad(plaintext.encode("utf-8"), AES.block_size))
    return base64.b64encode(encrypted).decode("ascii")


def protected_csrf_headers() -> dict[str, str]:
    alphabet = string.ascii_letters + string.digits
    key = "".join(secrets.choice(alphabet) for _ in range(32))
    encoded = base64.b64encode(key.encode("ascii")).decode("ascii")
    midpoint = len(encoded) // 2
    mixed = encoded[:midpoint] + encoded + encoded[midpoint:]
    return {
        "Csrf-Key": key,
        "Csrf-Value": hashlib.md5(mixed.encode("ascii")).hexdigest(),
    }


def encrypt_url_crypto_body(value: object, public_key_pem: str) -> tuple[str, str, bytes]:
    """Build the page's random AES body and RSA-encrypted AES header."""
    aes_key = secrets.token_bytes(16)
    plaintext = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    encrypted = AES.new(aes_key, AES.MODE_ECB).encrypt(pad(plaintext, AES.block_size))
    encrypted_key = PKCS1_v1_5.new(RSA.import_key(public_key_pem)).encrypt(
        base64.b64encode(aes_key)
    )
    return (
        base64.b64encode(encrypted).decode("ascii"),
        base64.b64encode(encrypted_key).decode("ascii"),
        aes_key,
    )


def decrypt_url_crypto_response(value: str, aes_key: bytes) -> object:
    """Unwrap a response recursively, matching getUnPackBody in the page."""
    current: object = value
    for _ in range(4):
        if not isinstance(current, str):
            return current
        try:
            parsed = json.loads(current)
        except json.JSONDecodeError:
            parsed = current
        if isinstance(parsed, str) and parsed != current:
            current = parsed
            continue
        if not isinstance(parsed, str):
            return parsed
        try:
            ciphertext = base64.b64decode(parsed, validate=True)
            decrypted = unpad(
                AES.new(aes_key, AES.MODE_ECB).decrypt(ciphertext), AES.block_size
            ).decode("utf-8")
        except (ValueError, TypeError, UnicodeDecodeError):
            return current
        try:
            current = json.loads(decrypted)
        except json.JSONDecodeError:
            current = decrypted
    return current
