import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional
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
from bit_login.service import jwb_login, jwb_cjd_login, jxzxehall_login
from bit_login.services.jwb import score, cjd
from bit_login.services.jxzxehall import course, credit
from bit_login.login import login_error

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
    "https://deploy-preview-57--bit101-demo.netlify.app",
    "http://deploy-preview-57--bit101-demo.netlify.app"
]
base_url = os.getenv("BASE_URL", "https://login.bit101.flwfdd.xyz")  


app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,       # 基础白名单
    allow_origin_regex=r'^https?://[a-zA-Z0-9\-]+\.bit101\.cn$',  # 子域名通配
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Pydantic Models ---

class BaseCredentials(BaseModel):
    username: str
    password: str

class JwbScoreRequest(BaseCredentials):
    kksj: Optional[str] = None
    detail: bool = False

class JwbAllScoreRequest(BaseCredentials):
    detailed: bool = False

class JxzxehallCoursesRequest(BaseCredentials):
    kksj: Optional[str] = None

# --- Session Management ---

class SessionManager:
    def __init__(self):
        self._cache = {}  # (username, service_name): (session, timestamp)
        self._ttl = 1800  # 30 minutes (Session timeout)
        self._lock = threading.Lock()
        self._key_locks = {}
        self._key_locks_lock = threading.Lock()

    def get_key_lock(self, username, service_name):
        key = (username, service_name)
        with self._key_locks_lock:
            if key not in self._key_locks:
                self._key_locks[key] = threading.RLock()
            return self._key_locks[key]

    def get_session(self, username, service_name):
        key = (username, service_name)
        with self._lock:
            if key in self._cache:
                session, timestamp = self._cache[key]
                if time.time() - timestamp < self._ttl:
                    return session
                else:
                    try:
                        del self._cache[key]
                        session.close()
                    except:
                        pass
        return None

    def set_session(self, username, service_name, session):
        key = (username, service_name)
        with self._lock:
            old_session = self._cache.get(key, (None, None))[0]
            self._cache[key] = (session, time.time())
        if old_session is not None and old_session is not session:
            old_session.close()

    def invalidate(self, username, service_name):
        key = (username, service_name)
        session = None
        with self._lock:
            if key in self._cache:
                session, _ = self._cache.pop(key)
        if session is not None:
            session.close()
    
    def cleanup_expired_sessions(self):
        """Clean up expired sessions periodically."""
        while True:
            time.sleep(300)  # Check every 5 minutes
            current_time = time.time()
            expired_keys = []
            
            with self._lock:
                for key, (session, timestamp) in list(self._cache.items()):
                    if current_time - timestamp > self._ttl:
                        expired_keys.append(key)
                
                for key in expired_keys:
                    try:
                        session, _ = self._cache.pop(key)
                        session.close()
                    except KeyError:
                        pass
            
            if expired_keys:
                logger.info(f"Cleaned up {len(expired_keys)} expired sessions.")

session_manager = SessionManager()

def get_service_session(login_cls, username, password, service_name):
    """Get session from cache or login."""
    # Try cache
    session = session_manager.get_session(username, service_name)
    if session:
        return session
    
    # Login
    logger.info(f"Performing fresh login for {username} - {service_name}")
    try:
        service_login = login_cls()
        service_login.login(username, password)
        session = service_login.get_session()
        session_manager.set_session(username, service_name, session)
        return session
    except login_error as e:
        logger.warning(f"Login failed for user {username}: {str(e)}")
        raise HTTPException(status_code=401, detail=f"Login failed: {str(e)}")
    except Exception as e:
        logger.error(f"Unexpected error during login for user {username}: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Internal server error during login: {str(e)}")

def execute_service(login_cls, service_cls, username, password, service_name, func_name, **kwargs):
    """
    Execute a service function with auto-retry mechanism.
    
    Args:
        login_cls: The login class (e.g., jwb_login)
        service_cls: The service wrapper class (e.g., jwb)
        username: User's username
        password: User's password
        service_name: Unique name for session caching (e.g., 'jwb')
        func_name: Name of the method to call on service_cls instance
        **kwargs: Arguments for the service method
    """
    
    # Function to create service instance and call method
    def call_service(s):
        srv = service_cls(s)
        method = getattr(srv, func_name)
        return method(**kwargs)

    with session_manager.get_key_lock(username, service_name):
        session = get_service_session(login_cls, username, password, service_name)
        
        try:
            # Attempt 1
            return call_service(session)
        except Exception as e:
            logger.info(f"First attempt failed for {username} on {service_name}.{func_name}. Reason: {str(e)}")
            
            # Invalidate cache and retry with fresh login
            session_manager.invalidate(username, service_name)
            session = get_service_session(login_cls, username, password, service_name)
            
            try:
                # Attempt 2
                return call_service(session)
            except Exception as final_e:
                logger.error(f"Second attempt failed for {username} on {service_name}.{func_name}. Reason: {str(final_e)}")
                raise HTTPException(status_code=500, detail=f"{str(final_e)}")

# --- Endpoints ---

@app.get("/")
async def root():
    return {"message": "BIT Login Services API is running"}

# --- JWB Services ---

@app.post("/api/jwb/score", summary="Get scores for a specific semester")
def get_jwb_score(request: JwbScoreRequest):
    """
    Get scores from JWB system.
    """
    result = execute_service(
        jwb_login, jwb, 
        request.username, request.password, 'jwb', 
        'get_score', 
        kksj=request.kksj, detailed=request.detailed
    )
    return {"data": result}

@app.post("/api/jwb/all_score", summary="Get all scores")
def get_jwb_all_score(request: JwbAllScoreRequest):
    """
    Get all scores from JWB system.
    """
    result = execute_service(
        jwb_login, score, 
        request.username, request.password, 'jwb', 
        'get_all_score', 
        detailed=request.detailed
    )
    return {"data": result}

# --- JWB bit101 Format Services ---

@app.post("/api/jwb/bit101/score", summary="Get bit101 format scores")
def get_jwb_bit101_score(request: JwbScoreRequest):
    """
    Get matching bit101 format scores from JWB system.
    """
    result = execute_service(
        jwb_login, score, 
        request.username, request.password, 'jwb', 
        'get_bit101_score', 
        kksj=request.kksj, detailed=request.detail
    )
    return {
        "msg": "查询成功OvO",
        "data": result
    }

@app.post("/api/jwb/cjd/img", summary="Get all scores")
def get_jwb_cjd_img(request: JwbAllScoreRequest):
    """
    Get all scores from JWB system.
    """
    result = execute_service(
        jwb_cjd_login, cjd, 
        request.username, request.password, 'jwb_cjd_img', 
        'get_cjd'
    )
    return {"data": {"url": result}}

# --- JXZXEHALL Services ---

@app.post("/api/jxzxehall/student_data", summary="Get student personal data")
def get_student_data(request: BaseCredentials):
    """
    Get student personal information from JXZXEHALL.
    """
    result = execute_service(
        jxzxehall_login, credit, 
        request.username, request.password, 'jxzxehall', 
        'get_student_data'
    )
    return {"data": result}

@app.post("/api/jxzxehall/credit", summary="Get student credit info")
def get_credit(request: BaseCredentials):
    """
    Get student credit information.
    """
    result = execute_service(
        jxzxehall_login, credit, 
        request.username, request.password, 'jxzxehall', 
        'get_credit'
    )
    return {"data": result}

@app.post("/api/jxzxehall/courses", summary="Get courses")
def get_courses(request: JxzxehallCoursesRequest):
    """
    Get student courses (schedule).
    """
    result = execute_service(
        jxzxehall_login, course, 
        request.username, request.password, 'jxzxehall', 
        'get_courses', 
        kksj=request.kksj
    )
    return {"data": result}


# --- Cookies ---
@app.post("/api/jwb/cookies", summary="Get JWB login cookies")
def get_jwb_cookies(request: BaseCredentials):
    """
    Get raw cookies after logging into JWB system and return formatted strings.
    """
    with session_manager.get_key_lock(request.username, 'jwb'):
        session = get_service_session(
            jwb_login, 
            request.username, 
            request.password, 
            'jwb'
        )
    
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
def get_jwb_cjd_cookies(request: BaseCredentials):
    """
    Get raw cookies after logging into JWB CJD system and return formatted strings.
    """
    with session_manager.get_key_lock(request.username, 'jwb_cjd'):
        session = get_service_session(
            jwb_cjd_login, 
            request.username, 
            request.password, 
            'jwb_cjd'
        )
    
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
def get_jxzxehall_cookies(request: BaseCredentials):
    """
    Get raw cookies after logging into JXZXEHALL (教学中心) system and return formatted strings.
    """
    with session_manager.get_key_lock(request.username, 'jxzxehall'):
        session = get_service_session(
            jxzxehall_login, 
            request.username, 
            request.password, 
            'jxzxehall'
        )
    
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
def generate_schedule_ics(request: JxzxehallCoursesRequest):
    global ICS_FILES
    ics_content, note = execute_service(
        jxzxehall_login, course, 
        request.username, request.password, 'jxzxehall', 
        'generate_ics', 
        kksj=request.kksj
    )
    
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
    
    session_cleanup_thread = threading.Thread(target=session_manager.cleanup_expired_sessions)
    session_cleanup_thread.daemon = True
    session_cleanup_thread.start()
    logger.info("Session cleanup background thread started.")

if __name__ == "__main__":
    uvicorn.run("server:app", host="0.0.0.0", port=16384, reload=True)
