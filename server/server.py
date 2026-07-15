import uvicorn
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from typing import List, Optional
import logging
import time
from fastapi.middleware.cors import CORSMiddleware
import os
import uuid
import threading
import glob
import requests
from fastapi.responses import FileResponse
# Import bit-login components
from bit_login.service import (
    cxcy_login,
    dekt_login,
    ibit_login,
    initialize_network,
    jwb_cjd_login,
    jwb_login,
    jxzxehall_login,
    library_login,
    webvpn_login,
    yanhekt_login,
)
from bit_login.services.jwb import score, cjd
from bit_login.services.jxzxehall import course, credit
from server.auth import ChallengeError, SQLiteChallengeStore

# Restored SQLite sessions may be consumed by a different worker than the one
# that logged in, so every worker must initialize its process-local URL map.
initialize_network()

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger("bit_login_server")

HTTP_CONNECT_TIMEOUT = float(os.getenv("HTTP_CONNECT_TIMEOUT", "5"))
HTTP_READ_TIMEOUT = float(os.getenv("HTTP_READ_TIMEOUT", "25"))
HTTP_TIMEOUT = (HTTP_CONNECT_TIMEOUT, HTTP_READ_TIMEOUT)

if not getattr(requests.sessions.Session.request, "_bit_login_timeout_patched", False):
    _original_session_request = requests.sessions.Session.request

    def _session_request_with_timeout(self, method, url, **kwargs):
        kwargs.setdefault("timeout", HTTP_TIMEOUT)
        return _original_session_request(self, method, url, **kwargs)

    _session_request_with_timeout._bit_login_timeout_patched = True
    requests.sessions.Session.request = _session_request_with_timeout

app = FastAPI(
    title="BIT Login Services API",
    description="High concurrency RESTful API for BIT services",
    version="1.0.0"
)

# 允许的域名列表
ALLOWED_ORIGINS = [
    "https://bit101.cn",
    "http://bit101.cn",
    "http://127.0.0.1:3000",
    "http://localhost:3000",
]
base_url = os.getenv("BASE_URL", "https://login.bit101.flwfdd.xyz")  


app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,       # 基础白名单
    allow_origin_regex=(
        r"^(?:https?://[a-zA-Z0-9-]+\.bit101\.cn|"
        r"https://deploy-preview-\d+--bit101-demo\.netlify\.app)$"
    ),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Pydantic Models ---

class BaseCredentials(BaseModel):
    username: Optional[str] = None
    password: Optional[str] = None
    challenge_id: Optional[str] = None

class AuthStartRequest(BaseCredentials):
    username: str
    password: str
    services: List[str] = Field(default_factory=lambda: ["jwb"])
    wait_seconds: float = Field(default=1.0, ge=0.0, le=5.0)

class SmsCodeRequest(BaseModel):
    code: str

class JwbScoreRequest(BaseCredentials):
    kksj: Optional[str] = None
    detail: bool = False
    detailed: Optional[bool] = None

class JwbAllScoreRequest(BaseCredentials):
    detailed: bool = False

class JxzxehallCoursesRequest(BaseCredentials):
    kksj: Optional[str] = None

AUTH_SERVICES = {
    "webvpn": webvpn_login,
    "jwb": jwb_login,
    "jwb_cjd": jwb_cjd_login,
    "jxzxehall": jxzxehall_login,
    "ibit": ibit_login,
    "yanhekt": yanhekt_login,
    "library": library_login,
    "dekt": dekt_login,
    "cxcy": cxcy_login,
}
challenge_store = SQLiteChallengeStore.from_env()


def _challenge_or_http(challenge_id, access_token):
    try:
        return challenge_store.authenticate(challenge_id, access_token or "")
    except ChallengeError as exc:
        status = 403 if "access token" in str(exc) else 404
        raise HTTPException(status_code=status, detail=str(exc)) from exc


def _challenge_from_header(authorization):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Bearer challenge token required")
    access_token = authorization[7:].strip()
    if not access_token:
        raise HTTPException(status_code=401, detail="Bearer challenge token required")
    return access_token


def _run_authentication(challenge_id, username, password, services):
    callback = lambda context: challenge_store.wait_for_sms(challenge_id, context)
    seed_session = None
    for service_name in services:
        service_session = requests.Session()
        if seed_session is not None:
            service_session.cookies.update(seed_session.cookies)
        login_cls = AUTH_SERVICES[service_name]
        service_login = login_cls(
            sms_code_callback=callback,
            session=service_session,
        )
        service_login.login(username, password)
        if seed_session is None:
            seed_session = service_login.get_session()
        challenge_store.store_service(
            challenge_id,
            service_name,
            service_login.get_session(),
            service_login.get_result(),
        )
    challenge_store.complete(challenge_id)


def _start_authentication_worker(username, password, services):
    handle = challenge_store.create(services)

    def authenticate() -> None:
        try:
            _run_authentication(
                handle.challenge_id,
                username,
                password,
                services,
            )
        except BaseException as exc:
            challenge_store.fail(handle.challenge_id, exc)

    threading.Thread(target=authenticate, daemon=True).start()
    return handle


def _credentials_or_http(request):
    if not request.username or not request.password:
        raise HTTPException(
            status_code=400,
            detail="username/password or an authenticated Bearer challenge is required",
        )
    return request.username, request.password


def _resolve_service_session(request, service_name, authorization):
    if authorization:
        access_token = _challenge_from_header(authorization)
        challenge_id = request.challenge_id
        if not challenge_id:
            raise HTTPException(
                status_code=400,
                detail="challenge_id is required when using a Bearer challenge",
            )
        try:
            return challenge_store.get_session(
                challenge_id, access_token, service_name
            )
        except ChallengeError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
    username, password = _credentials_or_http(request)
    handle = _start_authentication_worker(username, password, [service_name])
    challenge_store.wait_until_actionable(
        handle.challenge_id, handle.access_token, 1.0
    )
    snapshot = challenge_store.snapshot(
        handle.challenge_id,
        handle.access_token,
        include_access_token=True,
    )
    if snapshot["status"] != "authenticated":
        raise HTTPException(status_code=202, detail=snapshot)
    try:
        return challenge_store.get_session(
            handle.challenge_id, handle.access_token, service_name
        )
    except ChallengeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

def _score_detailed(request: JwbScoreRequest) -> bool:
    if request.detailed is not None:
        return request.detailed
    return request.detail

# --- Endpoints ---

@app.get("/")
async def root():
    return {"message": "BIT Login Services API is running"}


# --- Authentication Challenges ---

@app.post("/api/auth/start", status_code=202, summary="Start an SSO challenge")
def start_authentication(request: AuthStartRequest):
    services = list(dict.fromkeys(request.services))
    invalid = sorted(set(services) - set(AUTH_SERVICES))
    if not services or invalid:
        raise HTTPException(
            status_code=422,
            detail={
                "message": "invalid authentication services",
                "invalid": invalid,
                "supported": sorted(AUTH_SERVICES),
            },
        )
    handle = _start_authentication_worker(
        request.username, request.password, services
    )
    challenge_store.wait_until_actionable(
        handle.challenge_id, handle.access_token, request.wait_seconds
    )
    return challenge_store.snapshot(
        handle.challenge_id,
        handle.access_token,
        include_access_token=True,
    )


@app.get("/api/auth/{challenge_id}", summary="Get SSO challenge status")
def get_authentication_status(
    challenge_id: str,
    x_challenge_token: Optional[str] = Header(default=None),
):
    _challenge_or_http(challenge_id, x_challenge_token)
    return challenge_store.snapshot(challenge_id, x_challenge_token or "")


@app.post("/api/auth/{challenge_id}/sms", summary="Submit an SSO SMS code")
def submit_authentication_sms(
    challenge_id: str,
    request: SmsCodeRequest,
    x_challenge_token: Optional[str] = Header(default=None),
):
    _challenge_or_http(challenge_id, x_challenge_token)
    try:
        challenge_store.submit_sms(
            challenge_id, x_challenge_token or "", request.code
        )
    except ChallengeError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    challenge_store.wait_until_actionable(
        challenge_id, x_challenge_token or "", 1.0
    )
    return challenge_store.snapshot(challenge_id, x_challenge_token or "")


@app.get(
    "/api/auth/{challenge_id}/services/{service}",
    summary="Get a completed downstream login result",
)
def get_authentication_service_result(
    challenge_id: str,
    service: str,
    x_challenge_token: Optional[str] = Header(default=None),
):
    _challenge_or_http(challenge_id, x_challenge_token)
    if service not in AUTH_SERVICES:
        raise HTTPException(status_code=404, detail="unknown authentication service")
    try:
        result = challenge_store.get_result(
            challenge_id, x_challenge_token or "", service
        )
    except ChallengeError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {"service": service, "data": result}


@app.delete("/api/auth/{challenge_id}", summary="Delete an SSO challenge")
def delete_authentication(
    challenge_id: str,
    x_challenge_token: Optional[str] = Header(default=None),
):
    _challenge_or_http(challenge_id, x_challenge_token)
    challenge_store.delete(challenge_id, x_challenge_token or "")
    return {"status": "deleted"}

# --- JWB Services ---

@app.post("/api/jwb/score", summary="Get scores for a specific semester")
def get_jwb_score(
    request: JwbScoreRequest,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get scores from JWB system.
    """
    session = _resolve_service_session(request, "jwb", authorization)
    result = score(session).get_score(
        kksj=request.kksj, detailed=_score_detailed(request)
    )
    return {"data": result}

@app.post("/api/jwb/all_score", summary="Get all scores")
def get_jwb_all_score(
    request: JwbAllScoreRequest,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get all scores from JWB system.
    """
    session = _resolve_service_session(request, "jwb", authorization)
    result = score(session).get_all_score(detailed=request.detailed)
    return {"data": result}

# --- JWB bit101 Format Services ---

@app.post("/api/jwb/bit101/score", summary="Get bit101 format scores")
def get_jwb_bit101_score(
    request: JwbScoreRequest,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get matching bit101 format scores from JWB system.
    """
    session = _resolve_service_session(request, "jwb", authorization)
    result = score(session).get_bit101_score(
        kksj=request.kksj, detailed=_score_detailed(request)
    )
    return {
        "msg": "查询成功OvO",
        "data": result
    }

@app.post("/api/jwb/cjd/img", summary="Get all scores")
def get_jwb_cjd_img(
    request: JwbAllScoreRequest,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get all scores from JWB system.
    """
    session = _resolve_service_session(request, "jwb_cjd", authorization)
    result = cjd(session).get_cjd()
    return {"data": {"url": result}}

# --- JXZXEHALL Services ---

@app.post("/api/jxzxehall/student_data", summary="Get student personal data")
def get_student_data(
    request: BaseCredentials,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get student personal information from JXZXEHALL.
    """
    session = _resolve_service_session(request, "jxzxehall", authorization)
    result = credit(session).get_student_data()
    return {"data": result}

@app.post("/api/jxzxehall/credit", summary="Get student credit info")
def get_credit(
    request: BaseCredentials,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get student credit information.
    """
    session = _resolve_service_session(request, "jxzxehall", authorization)
    result = credit(session).get_credit()
    return {"data": result}

@app.post("/api/jxzxehall/courses", summary="Get courses")
def get_courses(
    request: JxzxehallCoursesRequest,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get student courses (schedule).
    """
    session = _resolve_service_session(request, "jxzxehall", authorization)
    result = course(session).get_courses(kksj=request.kksj)
    return {"data": result}


# --- Cookies ---
@app.post("/api/jwb/cookies", summary="Get JWB login cookies")
def get_jwb_cookies(
    request: BaseCredentials,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get raw cookies after logging into JWB system and return formatted strings.
    """
    session = _resolve_service_session(request, "jwb", authorization)
    
    try:
        cookies_dict = session.cookies.get_dict()
        cookie_str = "; ".join([f"{k}={v}" for k, v in cookies_dict.items()])
        
        return {
            "data": cookies_dict,
            "cookie_str": cookie_str
        }
    except Exception as e:
        logger.error(f"Failed to extract cookies for JWB: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to extract cookies from session")


@app.post("/api/jwb/cjd/cookies", summary="Get JWB CJD login cookies")
def get_jwb_cjd_cookies(
    request: BaseCredentials,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get raw cookies after logging into JWB CJD system and return formatted strings.
    """
    session = _resolve_service_session(request, "jwb_cjd", authorization)
    
    try:
        cookies_dict = session.cookies.get_dict()
        cookie_str = "; ".join([f"{k}={v}" for k, v in cookies_dict.items()])
        
        return {
            "data": cookies_dict,
            "cookie_str": cookie_str
        }
    except Exception as e:
        logger.error(f"Failed to extract cookies for JWBCJD: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to extract cookies from session")

@app.post("/api/jxzxehall/cookies", summary="Get JXZXEHALL login cookies")
def get_jxzxehall_cookies(
    request: BaseCredentials,
    authorization: Optional[str] = Header(default=None),
):
    """
    Get raw cookies after logging into JXZXEHALL (教学中心) system and return formatted strings.
    """
    session = _resolve_service_session(request, "jxzxehall", authorization)
    
    try:
        cookies_dict = session.cookies.get_dict()
        cookie_str = "; ".join([f"{k}={v}" for k, v in cookies_dict.items()])

        return {
            "data": cookies_dict,
            "cookie_str": cookie_str,
        }
    except Exception as e:
        logger.error(f"Failed to extract cookies for JXZXEHALL: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to extract cookies from session")


ICS_FILES = {}

@app.post("/api/jxzxehall/schedule_ics", summary="Generate ICS schedule file")
def generate_schedule_ics(
    request: JxzxehallCoursesRequest,
    authorization: Optional[str] = Header(default=None),
):
    global ICS_FILES
    session = _resolve_service_session(request, "jxzxehall", authorization)
    ics_content, note = course(session).generate_ics(kksj=request.kksj)
    
    file_uuid = str(uuid.uuid4())
    file_path = f"/tmp/{file_uuid}.ics"
    os.makedirs("/tmp", exist_ok=True)
    
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(ics_content)

    ICS_FILES[file_uuid] = {
        "url": f"{base_url}/tmp/{file_uuid}.ics",
        "file": file_path,
        "generated": time.time()
    }
        
    return {
        "url": f"{base_url}/tmp/{file_uuid}.ics",
        "note": note,
        "msg": "获取成功OvO"
    }

@app.get("/tmp/{filename}", summary="Download ICS file")
def download_ics(filename: str):
    """专门处理 /tmp/ 目录下的 ics 文件下载"""
    if not filename.endswith(".ics"):
        raise HTTPException(status_code=403, detail="Forbidden")
        
    file_path = f"/tmp/{filename}"
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="File not found or expired")
    
    return FileResponse(
        path=file_path, 
        filename="课程表.ics",        
        media_type="text/calendar", 
        content_disposition_type="attachment" 
    )

def clear_ics_files():
    """后台定时清理过期的 ics 文件及字典记录"""
    global ICS_FILES
    while True:
        time.sleep(30)
        current_time = time.time()
        expired_keys = []
        
        for k, v in list(ICS_FILES.items()):
            if current_time - v["generated"] > 30 * 60: # 30 min
                expired_keys.append(k)
                
        for k in expired_keys:
            file_path = ICS_FILES[k]['file']
            try:
                if os.path.exists(file_path):
                    os.remove(file_path)
            except Exception as e:
                logger.error(f"Failed to delete {file_path}: {str(e)}")
            finally:
                ICS_FILES.pop(k, None)


@app.on_event("startup")
def startup_event():
    for f in glob.glob("/tmp/*.ics"):
        try:
            os.remove(f)
        except:
            pass
        
    clear_thread = threading.Thread(target=clear_ics_files)
    clear_thread.daemon = True
    clear_thread.start()
    logger.info("ICS cleanup background thread started.")
    
if __name__ == "__main__":
    uvicorn.run("server:app", host="0.0.0.0", port=16384, reload=True)
