import logging
import os
import time
import uuid

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

from bit_login.service import jxzxehall_login
from bit_login.services.jxzxehall import course
from server.core.auth import execute_service
from server.core.config import BASE_URL
from server.schemas import JxzxehallCoursesRequest


logger = logging.getLogger("bit_login_server")
router = APIRouter()
ICS_FILES = {}


@router.post("/api/jxzxehall/schedule_ics", summary="Generate ICS schedule file")
def generate_schedule_ics(request: JxzxehallCoursesRequest):
    ics_content, note = execute_service(
        jxzxehall_login,
        course,
        request.username,
        request.password,
        "jxzxehall",
        "generate_ics",
        kksj=request.kksj,
    )
    file_uuid = str(uuid.uuid4())
    file_path = "/tmp/{}.ics".format(file_uuid)
    os.makedirs("/tmp", exist_ok=True)
    with open(file_path, "w", encoding="utf-8") as file:
        file.write(ics_content)
    ICS_FILES[file_uuid] = {"file": file_path, "generated": time.time()}
    return {
        "url": "{}/tmp/{}.ics".format(BASE_URL, file_uuid),
        "note": note,
        "msg": "获取成功OvO",
    }


@router.get("/tmp/{filename}", summary="Download ICS file")
def download_ics(filename: str):
    if not filename.endswith(".ics"):
        raise HTTPException(status_code=403, detail="Forbidden")
    file_path = "/tmp/{}".format(filename)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="File not found or expired")
    return FileResponse(
        path=file_path,
        filename="课程表.ics",
        media_type="text/calendar",
        content_disposition_type="attachment",
    )


def clear_ics_files():
    while True:
        time.sleep(30)
        current_time = time.time()
        for key, value in list(ICS_FILES.items()):
            if current_time - value["generated"] <= 30 * 60:
                continue
            try:
                if os.path.exists(value["file"]):
                    os.remove(value["file"])
            except Exception as e:
                logger.error("Failed to delete %s: %s", value["file"], str(e))
            finally:
                ICS_FILES.pop(key, None)


def remove_existing_ics_files():
    for filename in os.listdir("/tmp"):
        if not filename.endswith(".ics"):
            continue
        try:
            os.remove(os.path.join("/tmp", filename))
        except OSError:
            pass
