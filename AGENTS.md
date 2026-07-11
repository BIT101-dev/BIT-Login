# Repository Guide

## Layout

- `bit_login/login.py` implements the low-level BIT CAS ticket flow. `bit_login/service.py` builds service-specific authenticated `requests.Session` objects; `bit_login/services/` consumes those sessions for JWB and JXZXEHALL data APIs.
- `bit_login/__init__.py` is the public SDK surface. Keep exports there synchronized when adding or renaming public login/service classes.
- `server/server.py` is the FastAPI app. It adds request timeouts globally, caches sessions for 30 minutes by `(username, service)`, retries service calls once after re-login, and writes generated calendars to `/tmp`.
- URL and browser-header behavior is centralized in `bit_login/config.py`; avoid scattering replacement endpoints through service code.

## Setup And Verification

- Install the SDK and its declared dependencies from the repository root with `python -m pip install -e .`.
- Server-only runtime dependencies, including Gunicorn/Uvicorn, are in `server/requirements.txt`; its pinned `bit-login` line refers to PyPI, while `server/Dockerfile` deliberately removes that line and installs this checkout.
- There is no unit-test, lint, formatter, or typecheck configuration. Use `python -m compileall -q bit_login server` for offline syntax/import compilation.
- `python test.py` is a sequential live integration script, not a unit suite. It requires real `BITUSERNAME` and `BITPASSWORD` credentials and contacts BIT SSO, WebVPN, JWB, JXZXEHALL, iBIT, Yanhekt, and the library; do not run it without explicit access to test credentials. To focus a check, run a small one-service snippet rather than this all-services script.

## Running The API

- From the repository root, use `python -m uvicorn server.server:app --reload --port 16384` for development. The README commands naming root `server:app`, root `start.sh`, and port 8000 do not match the current tree.
- Production/container defaults are `HOST=0.0.0.0`, `PORT=16384`, and `WORKERS=4`. Build with `docker build -t bit-login-server -f server/Dockerfile .`.
- `server/start.sh` is container-oriented: it expects Docker's generated `server/__init__.py`. That file is not present in the checkout, so do not treat `bash server/start.sh` from the root as the canonical local launch path.
- Optional API environment variables are `HTTP_CONNECT_TIMEOUT` (default `5`), `HTTP_READ_TIMEOUT` (default `25`), and `BASE_URL` (used in returned ICS download URLs).

## Behavioral Constraints

- Constructing the first `BaseLogin` probes network reachability and mutates process-global `CONFIG["urls"]["active"]` plus `webvpn_mode`; later instances reuse that decision. Tests that exercise campus and WebVPN modes in one process must reset these globals explicitly.
- Login classes are stateful and chainable: call `.login(username, password)` before `.get_session()` or `.get_result()`. Preserve the returned `requests.Session`; downstream service wrappers depend on its cookies and modified headers.
- Accounts with MFA raise `second_auth_required` after password validation. Continue on the same login object with SMS (`send_sms_code()` then `verify_sms_code()`) or DingTalk QR (`begin_dingtalk_qr()` then poll `poll_dingtalk_qr()`); recreating the object loses CAS flow cookies and `execution` state.
- Authentication tests and API requests use real passwords. Never log, commit, fixture, snapshot, or place credentials in command history; prefer environment variables for the existing integration script.
- The package supports Python `>=3.6` in `setup.py`; do not introduce newer syntax unless that declared compatibility is intentionally changed.
