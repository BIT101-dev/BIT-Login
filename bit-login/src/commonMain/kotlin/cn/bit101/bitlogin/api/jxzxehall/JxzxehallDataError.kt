package cn.bit101.bitlogin.api.jxzxehall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import cn.bit101.bitlogin.http.HttpResponse

/**
 * Mirrors Python `bit_login.services.jxzxehall.JxzxehallDataError`.
 *
 * Raised when the teaching-center (教学中心) endpoints return non-OK status,
 * non-JSON bodies, or JSON missing the expected `datas.*` shape. The server
 * maps this to HTTP 422 to match `server.py` (`/api/jxzxehall/courses`,
 * `/api/jxzxehall/schedule_ics`).
 */
class JxzxehallDataError(message: String) : RuntimeException(message)

/**
 * Mirrors Python `_response_json(response, label)`: validate HTTP status and
 * JSON-decode the body, raising [JxzxehallDataError] (→ HTTP 422) on failure.
 */
internal fun responseJson(response: HttpResponse, label: String): JsonObject {
    if (response.status !in 200..299) {
        throw JxzxehallDataError("教学中心${label}接口返回 HTTP ${response.status}")
    }
    val value = try {
        Json.parseToJsonElement(response.bodyText)
    } catch (e: Throwable) {
        val contentType = (response.headers["Content-Type"] ?: "unknown").split(";").firstOrNull()?.trim() ?: "unknown"
        throw JxzxehallDataError("教学中心${label}接口未返回 JSON(类型 $contentType);无法解析")
    }
    if (value !is JsonObject) {
        throw JxzxehallDataError("教学中心${label}接口返回了无效数据")
    }
    return value
}
