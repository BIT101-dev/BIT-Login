import logging

from fastapi import APIRouter, HTTPException

from bit_login.service import jwb_cjd_login, jwb_login
from bit_login.services.jwb import cjd, score
from server.core.auth import execute_service, get_service_session
from server.core.sessions import session_manager
from server.schemas import BaseCredentials, JwbAllScoreRequest, JwbScoreRequest


logger = logging.getLogger("bit_login_server")
router = APIRouter(prefix="/api/jwb")


@router.post("/score", summary="Get scores for a specific semester")
def get_jwb_score(request: JwbScoreRequest):
    result = execute_service(
        jwb_login,
        score,
        request.username,
        request.password,
        "jwb",
        "get_score",
        kksj=request.kksj,
        detailed=request.detail,
    )
    return {"data": result}


@router.post("/all_score", summary="Get all scores")
def get_jwb_all_score(request: JwbAllScoreRequest):
    result = execute_service(
        jwb_login,
        score,
        request.username,
        request.password,
        "jwb",
        "get_all_score",
        detailed=request.detailed,
    )
    return {"data": result}


@router.post("/bit101/score", summary="Get bit101 format scores")
def get_jwb_bit101_score(request: JwbScoreRequest):
    result = execute_service(
        jwb_login,
        score,
        request.username,
        request.password,
        "jwb",
        "get_bit101_score",
        kksj=request.kksj,
        detailed=request.detail,
    )
    return {"msg": "查询成功OvO", "data": result}


@router.post("/cjd/img", summary="Get all scores")
def get_jwb_cjd_img(request: JwbAllScoreRequest):
    result = execute_service(
        jwb_cjd_login,
        cjd,
        request.username,
        request.password,
        "jwb_cjd_img",
        "get_cjd",
    )
    return {"data": {"url": result}}


def _get_cookies(request, login_cls, service_name, label):
    with session_manager.get_key_lock(request.username, service_name):
        session = get_service_session(
            login_cls, request.username, request.password, service_name
        )
    try:
        cookies = session.cookies.get_dict()
        return {
            "data": cookies,
            "cookie_str": "; ".join(["{}={}".format(k, v) for k, v in cookies.items()]),
        }
    except Exception as e:
        logger.error("Failed to extract cookies for %s: %s", label, str(e))
        raise HTTPException(
            status_code=500, detail="Failed to extract cookies from session"
        )


@router.post("/cookies", summary="Get JWB login cookies")
def get_jwb_cookies(request: BaseCredentials):
    return _get_cookies(request, jwb_login, "jwb", "JWB")


@router.post("/cjd/cookies", summary="Get JWB CJD login cookies")
def get_jwb_cjd_cookies(request: BaseCredentials):
    return _get_cookies(request, jwb_cjd_login, "jwb_cjd", "JWBCJD")
