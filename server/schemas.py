from typing import Optional

from pydantic import BaseModel


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


class SecondAuthRequest(BaseModel):
    username: str
    challenge_token: str


class SecondAuthCompleteRequest(SecondAuthRequest):
    password: str


class SmsVerifyRequest(SecondAuthCompleteRequest):
    code: str
