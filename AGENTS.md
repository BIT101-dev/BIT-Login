# Repository Guide

## Architecture

- `bit_login/login.py` owns the browser-form CAS flow; `bit_login/service.py` completes service-specific redirects and returns stateful authenticated `requests.Session` objects. Preserve sessions across all callback steps.
- MFA state and method implementations live in `bit_login/second_auth.py`. Add methods as `SecondAuthMethod` implementations registered with `SecondAuthFlow`; keep the compatibility methods on `login` and `BaseLogin`, including nested WebVPN MFA continuation.
- Keep public login/service exports synchronized in `bit_login/__init__.py`. Keep endpoint and browser-header changes centralized in `bit_login/config.py` rather than embedding replacement URLs in service code.
- `server/server.py` monkey-patches default timeouts onto every `requests.Session`, caches sessions for 30 minutes by `(username, service)`, and retries a failed service call once after re-login. The API does not expose the interactive MFA continuation used by the SDK/test script.

## Commands

- Use the committed uv environment: `uv sync`, then run tools with `uv run`; `.python-version` selects Python 3.14.
- There is no configured unit suite, linter, formatter, or type checker. Offline verification is `uv run python -m compileall -q bit_login server` plus `git diff --check`.
- `uv run python test.py --services jwb` runs one live integration target; accepted names are `webvpn`, `jwb`, `jwb_cjd`, `jxzxehall`, `ibit`, `yanhekt`, and `library`. Omitting `--services` contacts all of them sequentially.
- Local API command: `uv run python -m uvicorn server.server:app --reload --port 16384`. README commands using root `server:app`, root `start.sh`, or port 8000 are stale.
- Container build: `docker build -t bit-login-server -f server/Dockerfile .`. `server/start.sh` works in the image because the Dockerfile generates `server/__init__.py`; that file is absent locally.

## Live Auth

- `test.py` loads `.env` and requires real `BITUSERNAME`/`BITPASSWORD`; MFA optionally uses `BIT_SECOND_AUTH=sms|dingtalk` and `BIT_SMS_CODE`. Never run live integration without explicit credential/network approval, and never log, fixture, snapshot, commit, or put credentials directly in shell commands.
- Login objects are stateful. After `second_auth_required`, continue on that same object with SMS or DingTalk QR; recreating it loses CAS cookies and execution state. Service MFA must resume each subclass's `_complete_login`, not merely GET the CAS callback.
- The first `BaseLogin` probes network reachability and mutates process-global `CONFIG["urls"]["active"]`, `network_initialized`, and `webvpn_mode`. Reset all three when testing campus and WebVPN paths in one process.
- The declared compatibility floor is Python 3.6 in both manifests. Do not use newer syntax unless intentionally changing that contract.

## API Runtime

- Optional server variables are `HTTP_CONNECT_TIMEOUT=5`, `HTTP_READ_TIMEOUT=25`, and `BASE_URL`; `BASE_URL` only controls returned ICS download URLs.
- Generated calendars are written under `/tmp`, deleted at startup, and expire after 30 minutes. Server startup also launches daemon cleanup threads for calendars and cached sessions.
- `server/requirements.txt` pins the published `bit-login`; `server/Dockerfile` removes that line and installs this checkout. Preserve this distinction when changing packaging or container setup.
