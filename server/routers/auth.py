import base64

from fastapi import APIRouter, HTTPException

from bit_login.login import login_error
from server.core.auth import cache_authenticated_session, renew_challenge, restore_service_login
from server.core.mfa_state import challenge_ttl
from server.schemas import SecondAuthCompleteRequest, SecondAuthRequest, SmsVerifyRequest


router = APIRouter(prefix="/api/auth/second")


@router.post("/sms/send", summary="Send second-auth SMS code")
def send_second_auth_sms(request: SecondAuthRequest):
    payload, service_login = restore_service_login(request)
    try:
        service_login.send_sms_code()
        token = renew_challenge(payload, service_login)
        return {
            "status": "code_sent",
            "challenge_token": token,
            "expires_in": challenge_ttl(),
        }
    except login_error as e:
        raise HTTPException(
            status_code=502,
            detail={"code": "sms_send_failed", "message": str(e)},
        )


@router.post("/sms/verify", summary="Verify second-auth SMS code")
def verify_second_auth_sms(request: SmsVerifyRequest):
    payload, service_login = restore_service_login(request, require_password=True)
    try:
        service_login.verify_sms_code(request.code)
        return cache_authenticated_session(payload, service_login)
    except login_error as e:
        raise HTTPException(
            status_code=401,
            detail={"code": "second_auth_failed", "message": str(e)},
        )


@router.post("/dingtalk/begin", summary="Create second-auth DingTalk QR code")
def begin_second_auth_dingtalk(request: SecondAuthRequest):
    payload, service_login = restore_service_login(request)
    try:
        result = service_login.begin_dingtalk_qr()
        token = renew_challenge(payload, service_login)
        return {
            "status": result["status"],
            "login_id": result["login_id"],
            "qr_url": result["qr_url"],
            "qr_content": base64.b64encode(result["qr_content"]).decode("ascii"),
            "qr_content_type": result["qr_content_type"],
            "challenge_token": token,
            "expires_in": challenge_ttl(),
        }
    except login_error as e:
        raise HTTPException(
            status_code=502,
            detail={"code": "dingtalk_begin_failed", "message": str(e)},
        )


@router.post("/dingtalk/poll", summary="Poll second-auth DingTalk QR code")
def poll_second_auth_dingtalk(request: SecondAuthCompleteRequest):
    payload, service_login = restore_service_login(request, require_password=True)
    try:
        result = service_login.poll_dingtalk_qr()
        if isinstance(result, dict) and result.get("status") == "waiting":
            token = renew_challenge(payload, service_login)
            return {
                "status": "waiting",
                "code": result.get("code"),
                "challenge_token": token,
                "expires_in": challenge_ttl(),
            }
        return cache_authenticated_session(payload, service_login)
    except login_error as e:
        raise HTTPException(
            status_code=401,
            detail={"code": "second_auth_failed", "message": str(e)},
        )
