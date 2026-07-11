import logging

from fastapi import HTTPException

from bit_login.login import login_error, second_auth_required
from bit_login.service import jwb_cjd_login, jwb_login, jxzxehall_login
from server.core.config import ENABLED_MFA_METHODS, MFA_METHOD_NAMES
from server.core.mfa_state import (
    ChallengeConfigurationError,
    ExpiredChallenge,
    InvalidChallenge,
    challenge_ttl,
    issue_challenge,
    restore_challenge,
)
from server.core.sessions import session_manager


logger = logging.getLogger("bit_login_server")

SERVICE_LOGINS = {
    "jwb": jwb_login,
    "jwb_cjd": jwb_cjd_login,
    "jwb_cjd_img": jwb_cjd_login,
    "jxzxehall": jxzxehall_login,
}


def challenge_detail(service_name, username, service_login):
    token = issue_challenge(
        username,
        service_name,
        service_login.export_second_auth_state(),
    )
    auth = service_login.get_second_auth()
    return {
        "code": "second_auth_required",
        "message": "密码验证成功，需要二次认证",
        "challenge_token": token,
        "expires_in": challenge_ttl(),
        "service": service_name,
        "methods": [
            MFA_METHOD_NAMES[method]
            for method in ENABLED_MFA_METHODS
            if MFA_METHOD_NAMES[method] in auth["methods"]
        ],
        "phone": auth["phone"],
    }


def restore_service_login(request, require_password=False):
    try:
        payload = restore_challenge(request.challenge_token, request.username)
        login_cls = SERVICE_LOGINS.get(payload["service"])
        if login_cls is None:
            raise InvalidChallenge("二次认证服务无效")
        password = request.password if require_password else ""
        service_login = login_cls(initialize_network=False)
        service_login.restore_second_auth_state(
            payload["state"], payload["username"], password
        )
        return payload, service_login
    except ExpiredChallenge as e:
        raise HTTPException(
            status_code=410,
            detail={"code": "challenge_expired", "message": str(e)},
        )
    except InvalidChallenge as e:
        raise HTTPException(
            status_code=409,
            detail={"code": "invalid_challenge", "message": str(e)},
        )
    except ChallengeConfigurationError as e:
        logger.error("MFA challenge configuration error: %s", str(e))
        raise HTTPException(
            status_code=500,
            detail={"code": "mfa_configuration_error", "message": str(e)},
        )
    except login_error as e:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_challenge_state", "message": str(e)},
        )


def renew_challenge(payload, service_login):
    return issue_challenge(
        payload["username"],
        payload["service"],
        service_login.export_second_auth_state(),
    )


def cache_authenticated_session(payload, service_login):
    with session_manager.get_key_lock(payload["username"], payload["service"]):
        session_manager.set_session(
            payload["username"], payload["service"], service_login.get_session()
        )
    return {"status": "authenticated", "service": payload["service"]}


def get_service_session(login_cls, username, password, service_name):
    session = session_manager.get_session(username, service_name)
    if session:
        return session

    logger.info("Performing fresh login for %s - %s", username, service_name)
    try:
        service_login = login_cls()
        service_login.login(username, password)
        session = service_login.get_session()
        session_manager.set_session(username, service_name, session)
        return session
    except second_auth_required:
        try:
            detail = challenge_detail(service_name, username, service_login)
        except ChallengeConfigurationError as e:
            logger.error("MFA challenge configuration error: %s", str(e))
            raise HTTPException(
                status_code=500,
                detail={"code": "mfa_configuration_error", "message": str(e)},
            )
        raise HTTPException(status_code=428, detail=detail)
    except login_error as e:
        logger.warning("Login failed for user %s: %s", username, str(e))
        raise HTTPException(status_code=401, detail="Login failed: {}".format(e))
    except Exception as e:
        logger.error("Unexpected error during login for user %s: %s", username, str(e))
        raise HTTPException(
            status_code=500,
            detail="Internal server error during login: {}".format(e),
        )


def execute_service(
    login_cls, service_cls, username, password, service_name, func_name, **kwargs
):
    def call_service(session):
        return getattr(service_cls(session), func_name)(**kwargs)

    with session_manager.get_key_lock(username, service_name):
        session = get_service_session(login_cls, username, password, service_name)
        try:
            return call_service(session)
        except Exception as e:
            logger.info(
                "First attempt failed for %s on %s.%s. Reason: %s",
                username,
                service_name,
                func_name,
                str(e),
            )
            session_manager.invalidate(username, service_name)
            session = get_service_session(login_cls, username, password, service_name)
            try:
                return call_service(session)
            except Exception as final_e:
                logger.error(
                    "Second attempt failed for %s on %s.%s. Reason: %s",
                    username,
                    service_name,
                    func_name,
                    str(final_e),
                )
                raise HTTPException(status_code=500, detail=str(final_e))
