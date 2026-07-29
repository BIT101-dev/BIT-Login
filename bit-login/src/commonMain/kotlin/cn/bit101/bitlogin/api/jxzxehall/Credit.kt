package cn.bit101.bitlogin.api.jxzxehall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient

/**
 * 教学中心/一站式大厅 - 学生信息与学分. Mirrors Python `services/jxzxehall.py:credit`.
 */
class Credit(private val session: HttpClient) {

    suspend fun getStudentData(): JsonObject {
        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/xsfacx/modules/pyfacxepg/grpyfacx.do"
        val raw = session.get(url).bodyText
        val json = Json.parseToJsonElement(raw).jsonObject
        val row = json["datas"]!!.jsonObject["grpyfacx"]!!.jsonObject["rows"]!!.jsonArray[0].jsonObject
        return buildMap {
            put("name", row.str("XM"))
            put("student_code", row.str("XH"))
            put("major", row.str("ZYDM_DISPLAY"))
            put("class", row.str("BJDM_DISPLAY"))
            put("grade", row.str("XZNJ_DISPLAY"))
            put("gender", row.str("XBDM_DISPLAY"))
            put("college", row.str("YXDM_DISPLAY"))
            put("total_credit", row.str("ZSYQXF"))
            put("completed_credit", row.str("YWCXF"))
            put("required_credit", row.str("ZSYQXFXSZ"))
            put("id", row.str("WID"))
            put("detail", buildMap {
                put("pyfadm", row.str("PYFADM"))
                put("zydm", row.str("ZYDM"))
                put("xdlxdm", row.str("XDLXDM"))
                put("xdlxdm_display", row.str("XDLXDM_DISPLAY"))
                put("xbdm", row.str("XBDM"))
                put("xbdm_display", row.str("XBDM_DISPLAY"))
                put("zydm_display", row.str("ZYDM_DISPLAY"))
                put("yxdm", row.str("YXDM"))
                put("yxdm_display", row.str("YXDM_DISPLAY"))
                put("wxdm", row.str("WID"))
                put("xznj", row.str("XZNJ"))
                put("xznj_display", row.str("XZNJ_DISPLAY"))
            })
        }.toJsonObject()
    }

    suspend fun getCredit(): Map<String, String> {
        val data = getStudentData()
        return mapOf(
            "total_credit" to data["total_credit"]!!.jsonPrimitive.content,
            "completed_credit" to data["completed_credit"]!!.jsonPrimitive.content,
            "required_credit" to data["required_credit"]!!.jsonPrimitive.content,
        )
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
}

internal fun Map<String, Any?>.toJsonObject(): JsonObject = kotlinx.serialization.json.buildJsonObject {
    forEach { (k, v) ->
        when (v) {
            null -> {}
            is String -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
            is Number -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
            is Boolean -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                put(k, (v as Map<String, Any?>).toJsonObject())
            }
            is JsonArray -> put(k, v)
        }
    }
}

private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
