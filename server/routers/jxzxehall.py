import logging

from fastapi import APIRouter, HTTPException

from bit_login.service import jxzxehall_login
from bit_login.services.jxzxehall import course, credit
from server.core.auth import execute_service, get_service_session
from server.core.sessions import session_manager
from server.schemas import BaseCredentials, JxzxehallCoursesRequest


logger = logging.getLogger("bit_login_server")
router = APIRouter(prefix="/api/jxzxehall")


@router.post("/student_data", summary="Get student personal data")
def get_student_data(request: BaseCredentials):
    result = execute_service(
        jxzxehall_login,
        credit,
        request.username,
        request.password,
        "jxzxehall",
        "get_student_data",
    )
    return {"data": result}


@router.post("/credit", summary="Get student credit info")
def get_credit(request: BaseCredentials):
    result = execute_service(
        jxzxehall_login,
        credit,
        request.username,
        request.password,
        "jxzxehall",
        "get_credit",
    )
    return {"data": result}


@router.post("/courses", summary="Get courses")
def get_courses(request: JxzxehallCoursesRequest):
    result = execute_service(
        jxzxehall_login,
        course,
        request.username,
        request.password,
        "jxzxehall",
        "get_courses",
        kksj=request.kksj,
    )
    return {"data": result}


@router.post("/cookies", summary="Get JXZXEHALL login cookies")
def get_jxzxehall_cookies(request: BaseCredentials):
    with session_manager.get_key_lock(request.username, "jxzxehall"):
        session = get_service_session(
            jxzxehall_login,
            request.username,
            request.password,
            "jxzxehall",
        )
    try:
        cookies = session.cookies.get_dict()
        return {
            "data": cookies,
            "cookie_str": "; ".join(["{}={}".format(k, v) for k, v in cookies.items()]),
        }
    except Exception as e:
        logger.error("Failed to extract cookies for JXZXEHALL: %s", str(e))
        raise HTTPException(
            status_code=500, detail="Failed to extract cookies from session"
        )
