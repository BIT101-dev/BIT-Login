package cn.bit101.bitlogin.api.jxzxehall

/**
 * Mirrors Python `bit_login.services.jxzxehall.JxzxehallDataError`.
 *
 * Raised when the teaching-center (教学中心) endpoints return non-OK status,
 * non-JSON bodies, or JSON missing the expected `datas.*` shape. The server
 * maps this to HTTP 422 to match `server.py` (`/api/jxzxehall/courses`,
 * `/api/jxzxehall/schedule_ics`).
 */
class JxzxehallDataError(message: String) : RuntimeException(message)
