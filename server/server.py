import logging
import threading

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from server.core.auth import execute_service, get_service_session
from server.core.config import ALLOWED_ORIGINS
from server.core.http import configure_default_timeout
from server.core.sessions import session_manager
from server.routers.auth import router as auth_router
from server.routers.ics import clear_ics_files, remove_existing_ics_files
from server.routers.ics import router as ics_router
from server.routers.jwb import router as jwb_router
from server.routers.jxzxehall import router as jxzxehall_router


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("bit_login_server")

configure_default_timeout()

app = FastAPI(
    title="BIT Login Services API",
    description="High concurrency RESTful API for BIT services",
    version="1.0.0",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_origin_regex=r"^https?://[a-zA-Z0-9\-]+\.bit101\.cn$",
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
for router in (auth_router, jwb_router, jxzxehall_router, ics_router):
    for route in router.routes:
        app.router.routes.append(route)


@app.get("/")
async def root():
    return {"message": "BIT Login Services API is running"}


@app.on_event("startup")
def startup_event():
    remove_existing_ics_files()

    ics_cleanup = threading.Thread(target=clear_ics_files)
    ics_cleanup.daemon = True
    ics_cleanup.start()
    logger.info("ICS cleanup background thread started.")

    session_cleanup = threading.Thread(target=session_manager.cleanup_expired_sessions)
    session_cleanup.daemon = True
    session_cleanup.start()
    logger.info("Session cleanup background thread started.")


if __name__ == "__main__":
    uvicorn.run("server.server:app", host="0.0.0.0", port=16384, reload=True)
