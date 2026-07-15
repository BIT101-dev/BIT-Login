import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, Mapping, Optional

import requests

from bit_login.sso.models import SmsCodeContext


class ChallengeError(Exception):
    """A shared authentication challenge cannot perform a transition."""


_SENSITIVE_ERROR_PARAMETER = re.compile(
    r"(?i)(\b(?:access[_-]?token|response[_-]?token|ticket|cas|sms[_-]?code|"
    r"password|passwd)\b"
    r"\s*[=:]\s*)([^\s&,;\])}]+)"
)
_BEARER_TOKEN = re.compile(r"(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+")


def serialize_session(session: requests.Session) -> Dict[str, Any]:
    return {
        "cookies": [
            {
                "name": cookie.name,
                "value": cookie.value,
                "domain": cookie.domain,
                "path": cookie.path,
                "secure": cookie.secure,
                "expires": cookie.expires,
            }
            for cookie in session.cookies
        ],
        "headers": dict(session.headers),
        "trust_env": bool(session.trust_env),
    }


def restore_session(value: Mapping[str, Any]) -> requests.Session:
    session = requests.Session()
    headers = value.get("headers")
    if isinstance(headers, Mapping):
        session.headers.clear()
        session.headers.update({str(key): str(item) for key, item in headers.items()})
    session.trust_env = bool(value.get("trust_env", True))
    cookies = value.get("cookies")
    if isinstance(cookies, list):
        for item in cookies:
            if not isinstance(item, Mapping) or not item.get("name"):
                continue
            kwargs = {
                key: item[key]
                for key in ("domain", "path", "secure", "expires")
                if item.get(key) is not None
            }
            session.cookies.set(
                str(item["name"]), str(item.get("value") or ""), **kwargs
            )
    return session


@dataclass(frozen=True)
class ChallengeHandle:
    challenge_id: str
    access_token: str


class SQLiteChallengeStore:
    """SQLite WAL-backed challenge state shared by Gunicorn workers."""

    def __init__(
        self,
        database: str,
        *,
        pending_ttl: int = 300,
        ready_ttl: int = 1800,
        poll_interval: float = 0.25,
    ) -> None:
        self.database = str(Path(database).expanduser().resolve())
        self.pending_ttl = int(pending_ttl)
        self.ready_ttl = int(ready_ttl)
        self.poll_interval = float(poll_interval)
        Path(self.database).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        descriptor = os.open(self.database, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(descriptor)
        os.chmod(self.database, 0o600)
        self._initialize()
        self._harden_files()

    @classmethod
    def from_env(cls) -> "SQLiteChallengeStore":
        return cls(
            os.getenv("AUTH_DB_PATH", "/tmp/bit-login/auth.db"),
            pending_ttl=int(os.getenv("AUTH_CHALLENGE_TTL", "300")),
            ready_ttl=int(os.getenv("AUTH_SESSION_TTL", "1800")),
        )

    def create(self, services: Iterable[str]) -> ChallengeHandle:
        self.cleanup()
        challenge_id = secrets.token_urlsafe(18)
        access_token = secrets.token_urlsafe(32)
        now = time.time()
        with self._transaction() as connection:
            connection.execute(
                """
                INSERT INTO auth_challenges (
                    challenge_id, token_hash, status, requested_services,
                    ready_services, masked_phone, sms_purpose, error,
                    created_at, expires_at
                ) VALUES (?, ?, 'running', ?, '[]', '', '', '', ?, ?)
                """,
                (
                    challenge_id,
                    self._token_hash(access_token),
                    self._encode_json(list(services)),
                    now,
                    now + self.pending_ttl,
                ),
            )
        return ChallengeHandle(challenge_id, access_token)

    def authenticate(self, challenge_id: str, access_token: str) -> Dict[str, Any]:
        state = self._state(challenge_id)
        if not hmac.compare_digest(
            str(state.get("token_hash") or ""), self._token_hash(access_token)
        ):
            raise ChallengeError("invalid challenge access token")
        return state

    def snapshot(
        self,
        challenge_id: str,
        access_token: str,
        *,
        include_access_token: bool = False,
    ) -> Dict[str, Any]:
        state = self.authenticate(challenge_id, access_token)
        result = {
            "challenge_id": challenge_id,
            "status": state["status"],
            "requested_services": state["requested_services"],
            "ready_services": state["ready_services"],
            "expires_in": max(0, int(state["expires_at"] - time.time())),
        }
        if state["status"] == "waiting_sms":
            result["masked_phone"] = state["masked_phone"] or "绑定手机"
            result["sms_purpose"] = (
                state["sms_purpose"] or "password_second_factor"
            )
        if state["status"] == "failed":
            result["error"] = state["error"] or "authentication failed"
        if include_access_token:
            result["access_token"] = access_token
        return result

    def wait_for_sms(self, challenge_id: str, context: SmsCodeContext) -> str:
        self._update_challenge(
            challenge_id,
            status="waiting_sms",
            masked_phone=context.masked_phone or "绑定手机",
            sms_purpose=context.purpose,
            expires_at=time.time() + self.pending_ttl,
        )
        deadline = time.monotonic() + self.pending_ttl
        while time.monotonic() < deadline:
            code = self._take_sms_code(challenge_id)
            if code is not None:
                self._update_challenge(challenge_id, status="processing")
                return code
            state = self._state(challenge_id)
            if state["status"] in {"cancelled", "expired", "failed"}:
                raise ChallengeError(f"challenge is {state['status']}")
            time.sleep(self.poll_interval)
        self.fail(challenge_id, ChallengeError("SMS challenge expired"), status="expired")
        raise ChallengeError("SMS challenge expired")

    def submit_sms(self, challenge_id: str, access_token: str, code: str) -> None:
        code = str(code).strip()
        if not re.fullmatch(r"[0-9]{4,8}", code):
            raise ChallengeError("SMS code must contain 4 to 8 digits")
        self.authenticate(challenge_id, access_token)
        now = time.time()
        with self._transaction(immediate=True) as connection:
            row = connection.execute(
                "SELECT status FROM auth_challenges WHERE challenge_id = ?",
                (challenge_id,),
            ).fetchone()
            if row is None:
                raise ChallengeError("unknown or expired authentication challenge")
            if row["status"] != "waiting_sms":
                raise ChallengeError("challenge is not waiting for an SMS code")
            try:
                connection.execute(
                    """
                    INSERT INTO auth_sms_codes (challenge_id, code, expires_at)
                    VALUES (?, ?, ?)
                    """,
                    (challenge_id, code, now + self.pending_ttl),
                )
            except sqlite3.IntegrityError as exc:
                raise ChallengeError("an SMS code has already been submitted") from exc
            connection.execute(
                """
                UPDATE auth_challenges
                SET status = 'processing', expires_at = ?
                WHERE challenge_id = ?
                """,
                (now + self.pending_ttl, challenge_id),
            )

    def store_service(
        self,
        challenge_id: str,
        service: str,
        session: requests.Session,
        result: Optional[Mapping[str, Any]] = None,
    ) -> None:
        payload = {
            "session": serialize_session(session),
            "result": dict(result or {}),
        }
        payload_json = self._encode_json(payload)
        now = time.time()
        expires_at = now + self.ready_ttl
        with self._transaction(immediate=True) as connection:
            row = connection.execute(
                "SELECT ready_services FROM auth_challenges WHERE challenge_id = ?",
                (challenge_id,),
            ).fetchone()
            if row is None:
                raise ChallengeError("unknown or expired authentication challenge")
            ready_services = self._decode_list(row["ready_services"])
            if service not in ready_services:
                ready_services.append(service)
            connection.execute(
                """
                INSERT INTO auth_service_sessions (
                    challenge_id, service, payload, expires_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(challenge_id, service) DO UPDATE SET
                    payload = excluded.payload,
                    expires_at = excluded.expires_at
                """,
                (challenge_id, service, payload_json, expires_at),
            )
            connection.execute(
                """
                UPDATE auth_challenges
                SET ready_services = ?, expires_at = ?
                WHERE challenge_id = ?
                """,
                (
                    self._encode_json(sorted(ready_services)),
                    now + self.pending_ttl,
                    challenge_id,
                ),
            )

    def complete(self, challenge_id: str) -> None:
        expires_at = time.time() + self.ready_ttl
        with self._transaction(immediate=True) as connection:
            cursor = connection.execute(
                """
                UPDATE auth_challenges
                SET status = 'authenticated', masked_phone = '',
                    sms_purpose = '', expires_at = ?
                WHERE challenge_id = ?
                """,
                (expires_at, challenge_id),
            )
            if cursor.rowcount != 1:
                raise ChallengeError("unknown or expired authentication challenge")
            connection.execute(
                """
                UPDATE auth_service_sessions SET expires_at = ?
                WHERE challenge_id = ?
                """,
                (expires_at, challenge_id),
            )

    def fail(
        self, challenge_id: str, error: BaseException, *, status: str = "failed"
    ) -> None:
        try:
            self._update_challenge(
                challenge_id,
                status=status,
                error=self._safe_error(error),
            )
        except ChallengeError:
            return

    def get_session(
        self, challenge_id: str, access_token: str, service: str
    ) -> requests.Session:
        state = self.authenticate(challenge_id, access_token)
        if state["status"] != "authenticated":
            raise ChallengeError(f"challenge is {state['status']}")
        payload = self._service_payload(challenge_id, service)
        return restore_session(payload.get("session") or {})

    def get_result(
        self, challenge_id: str, access_token: str, service: str
    ) -> Dict[str, Any]:
        state = self.authenticate(challenge_id, access_token)
        if state["status"] != "authenticated":
            raise ChallengeError(f"challenge is {state['status']}")
        result = self._service_payload(challenge_id, service).get("result")
        return dict(result) if isinstance(result, Mapping) else {}

    def delete(self, challenge_id: str, access_token: str) -> None:
        self.authenticate(challenge_id, access_token)
        with self._transaction(immediate=True) as connection:
            connection.execute(
                "DELETE FROM auth_challenges WHERE challenge_id = ?", (challenge_id,)
            )

    def cleanup(self) -> int:
        now = time.time()
        with self._transaction(immediate=True) as connection:
            cursor = connection.execute(
                "DELETE FROM auth_challenges WHERE expires_at <= ?", (now,)
            )
            connection.execute(
                "DELETE FROM auth_sms_codes WHERE expires_at <= ?", (now,)
            )
            connection.execute(
                "DELETE FROM auth_service_sessions WHERE expires_at <= ?", (now,)
            )
            return max(0, int(cursor.rowcount))

    def wait_until_actionable(
        self, challenge_id: str, access_token: str, timeout: float
    ) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status = self.authenticate(challenge_id, access_token)["status"]
            if status in {"waiting_sms", "authenticated", "failed", "expired"}:
                return
            time.sleep(min(self.poll_interval, max(0, deadline - time.monotonic())))

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=NORMAL")
            sms_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(auth_sms_codes)")
            }
            session_columns = {
                row["name"]
                for row in connection.execute(
                    "PRAGMA table_info(auth_service_sessions)"
                )
            }
            legacy_schema = (sms_columns and "code" not in sms_columns) or (
                session_columns and "payload" not in session_columns
            )
            challenge_table = connection.execute(
                """
                SELECT 1 FROM sqlite_master
                WHERE type = 'table' AND name = 'auth_challenges'
                """
            ).fetchone()
            if legacy_schema:
                # Authentication state is short-lived, so discard the old
                # encrypted schema instead of retaining an unusable migration key.
                connection.executescript(
                    """
                    DROP TABLE IF EXISTS auth_sms_codes;
                    DROP TABLE IF EXISTS auth_service_sessions;
                    DROP TABLE IF EXISTS auth_challenges;
                    """
                )
            elif challenge_table and (not sms_columns or not session_columns):
                connection.execute("DELETE FROM auth_challenges")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS auth_challenges (
                    challenge_id TEXT PRIMARY KEY,
                    token_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    requested_services TEXT NOT NULL,
                    ready_services TEXT NOT NULL,
                    masked_phone TEXT NOT NULL,
                    sms_purpose TEXT NOT NULL,
                    error TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_auth_challenges_expires
                    ON auth_challenges(expires_at);

                CREATE TABLE IF NOT EXISTS auth_sms_codes (
                    challenge_id TEXT PRIMARY KEY
                        REFERENCES auth_challenges(challenge_id) ON DELETE CASCADE,
                    code TEXT NOT NULL,
                    expires_at REAL NOT NULL
                );

                CREATE TABLE IF NOT EXISTS auth_service_sessions (
                    challenge_id TEXT NOT NULL
                        REFERENCES auth_challenges(challenge_id) ON DELETE CASCADE,
                    service TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    expires_at REAL NOT NULL,
                    PRIMARY KEY (challenge_id, service)
                );
                """
            )

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(
            self.database,
            timeout=10,
            isolation_level=None,
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA busy_timeout=10000")
        try:
            yield connection
        finally:
            connection.close()
            self._harden_files()

    @contextmanager
    def _transaction(
        self, *, immediate: bool = False
    ) -> Iterator[sqlite3.Connection]:
        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE" if immediate else "BEGIN")
            try:
                yield connection
            except BaseException:
                connection.rollback()
                raise
            else:
                connection.commit()

    def _state(self, challenge_id: str) -> Dict[str, Any]:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM auth_challenges WHERE challenge_id = ?",
                (challenge_id,),
            ).fetchone()
        if row is None:
            raise ChallengeError("unknown or expired authentication challenge")
        state = dict(row)
        if float(state["expires_at"]) <= time.time():
            self.cleanup()
            raise ChallengeError("unknown or expired authentication challenge")
        state["requested_services"] = self._decode_list(state["requested_services"])
        state["ready_services"] = self._decode_list(state["ready_services"])
        return state

    def _update_challenge(self, challenge_id: str, **fields: Any) -> None:
        allowed = {"status", "masked_phone", "sms_purpose", "error", "expires_at"}
        if not fields or set(fields) - allowed:
            raise ValueError("invalid challenge update")
        assignments = ", ".join(f"{name} = ?" for name in fields)
        values = list(fields.values()) + [challenge_id]
        with self._transaction(immediate=True) as connection:
            cursor = connection.execute(
                f"UPDATE auth_challenges SET {assignments} WHERE challenge_id = ?",
                values,
            )
            if cursor.rowcount != 1:
                raise ChallengeError("unknown or expired authentication challenge")

    def _take_sms_code(self, challenge_id: str) -> Optional[str]:
        with self._transaction(immediate=True) as connection:
            row = connection.execute(
                """
                SELECT code FROM auth_sms_codes
                WHERE challenge_id = ? AND expires_at > ?
                """,
                (challenge_id, time.time()),
            ).fetchone()
            if row is None:
                return None
            connection.execute(
                "DELETE FROM auth_sms_codes WHERE challenge_id = ?", (challenge_id,)
            )
            return str(row["code"])

    def _service_payload(self, challenge_id: str, service: str) -> Dict[str, Any]:
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT payload FROM auth_service_sessions
                WHERE challenge_id = ? AND service = ? AND expires_at > ?
                """,
                (challenge_id, service, time.time()),
            ).fetchone()
        if row is None:
            raise ChallengeError(f"service is not ready: {service}")
        try:
            value = json.loads(row["payload"])
        except (json.JSONDecodeError, TypeError) as exc:
            raise ChallengeError("stored service session is invalid") from exc
        if not isinstance(value, dict):
            raise ChallengeError("stored service session is invalid")
        return value

    @staticmethod
    def _encode_json(value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _decode_list(value: str) -> list[str]:
        try:
            decoded = json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return []
        return [str(item) for item in decoded] if isinstance(decoded, list) else []

    @staticmethod
    def _token_hash(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    @staticmethod
    def _safe_error(error: BaseException) -> str:
        value = str(error) or error.__class__.__name__
        value = _SENSITIVE_ERROR_PARAMETER.sub(r"\1[redacted]", value)
        value = _BEARER_TOKEN.sub(r"\1[redacted]", value)
        return value[:500]

    def _harden_files(self) -> None:
        for suffix in ("", "-wal", "-shm"):
            try:
                os.chmod(f"{self.database}{suffix}", 0o600)
            except FileNotFoundError:
                pass
