package cn.bit101.bitlogin.api.jxzxehall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 空闲教室查询. Mirrors Python `services/jxzxehall.py:classroom`.
 */
class Classroom(private val session: HttpClient) {

    suspend fun getOccupancy(
        dateStr: String,
        semester: String? = null,
        week: Int? = null,
        campusCode: String? = null,
        buildingCode: String? = null,
        classroomName: String? = null,
    ): List<Map<String, Any?>> {
        var dayOfWeek = try {
            LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")).dayOfWeek.value
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("日期格式错误，应为 YYYY-MM-DD", e)
        }

        var effectiveSemester = semester
        var effectiveWeek = week

        if (effectiveSemester.isNullOrBlank() || effectiveWeek == null) {
            val termInfo = getCurrentTermInfo() ?: throw RuntimeException("无法获取当前学期信息，请手动指定 semester 和 week")
            if (effectiveSemester.isNullOrBlank()) effectiveSemester = termInfo["DM"] as String
            if (effectiveWeek == null) {
                val xn = termInfo["XNDM"] as String
                val xq = termInfo["XQDM"] as String
                val weekInfo = getWeekInfo(dateStr, xn, xq)
                if (weekInfo != null) {
                    effectiveWeek = (weekInfo["ZC"] as? String)?.toIntOrNull() ?: 1
                    (weekInfo["XQJ"] as? String)?.toIntOrNull()?.let { dayOfWeek = it }
                } else {
                    effectiveWeek = 1
                }
            }
        }

        val querySetting: MutableList<Map<String, String>> = mutableListOf()
        if (campusCode != null) querySetting += mapOf(
            "name" to "XXXQDM", "caption" to "校区代码", "builder" to "equal",
            "linkOpt" to "AND", "value" to campusCode,
        )
        if (buildingCode != null) querySetting += mapOf(
            "name" to "JXLDM", "caption" to "教学楼代码", "builder" to "equal",
            "linkOpt" to "AND", "value" to buildingCode,
        )
        if (classroomName != null) querySetting += mapOf(
            "name" to "JASMC", "caption" to "教学楼名称", "builder" to "include",
            "linkOpt" to "AND", "value" to classroomName,
        )

        // 先访问应用首页以初始化应用会话, 否则后端可能返回 401
        val indexResponse = session.get(
            "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/kxjas/*default/index.do",
            headers = Config.Headers.jxzxehall,
        )

        val allClassrooms = mutableListOf<Map<String, Any?>>()
        val pageSize = 50
        var pageNumber = 1

        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/kxjas/modules/kxjas/cxjsqk.do?vpn-12-o2-jxzxehallapp.bit.edu.cn"
        while (true) {
            val payload = mapOf(
                "XNXQDM" to effectiveSemester!!,
                "ZC" to effectiveWeek.toString(),
                "XQ" to dayOfWeek.toString(),
                "RQ" to dateStr,
                "querySetting" to Json.encodeToString(kotlinx.serialization.serializer(), querySetting),
                "*order" to "+JASMC",
                "pageSize" to pageSize.toString(),
                "pageNumber" to pageNumber.toString(),
            )
            val response = session.post(url, headers = Config.Headers.base, data = payload)
            if (response.status != 200) {
                val sentCookies = session.cookieStorage.get(io.ktor.http.Url(url)).joinToString(",") { it.name }
                val storedCookies = session.cookieStorage.snapshot().joinToString(",") { "${it.name}@${it.domain}:${it.path}" }
                throw RuntimeException(
                    "请求失败，HTTP 状态码: ${response.status}, " +
                        "URL: $url, " +
                        "IndexStatus: ${indexResponse.status}, " +
                        "Location: ${response.location()}, " +
                        "SentCookies: $sentCookies, " +
                        "StoredCookies: $storedCookies, " +
                        "Body: ${response.bodyText.take(800)}"
                )
            }
            val data = Json.parseToJsonElement(response.bodyText).jsonObject
            val pageData = data["datas"]?.jsonObject?.get("cxjsqk")?.jsonObject
                ?: throw RuntimeException("获取数据失败，可能是 Cookie 已过期或参数错误")
            val rows = pageData["rows"]?.jsonArray ?: JsonArray(emptyList())

            for (row in rows) {
                val obj = row.jsonObject
                val jasmc = obj["JASMC"]?.jsonPrimitive?.content
                val roomInfo = linkedMapOf<String, Any?>(
                    "name" to jasmc,
                    "building_code" to obj["JXLDM"]?.jsonPrimitive?.contentOrNullSafe(),
                    "type" to obj["JASLXDM_DISPLAY"]?.jsonPrimitive?.contentOrNullSafe(),
                    "seats" to obj["SKZWS"]?.jsonPrimitive?.contentOrNullSafe(),
                    "coordinates" to getBuildingCoord(jasmc),
                    "status" to LinkedHashMap<Int, Map<String, String>>(),
                )
                @Suppress("UNCHECKED_CAST")
                val statusMap = roomInfo["status"] as java.util.LinkedHashMap<Int, Map<String, String>>
                for (i in 1..13) {
                    val jcKey = "JC$i"
                    val statusStr = obj[jcKey]?.jsonPrimitive?.contentOrNullSafe() ?: ""
                    val parsed = parseJcStatus(statusStr)
                    val (start, end) = if (i - 1 < TIME_TABLE.size) {
                        TIME_TABLE[i - 1].first to TIME_TABLE[i - 1].second
                    } else null to null
                    statusMap[i] = mapOf(
                        "state" to parsed,
                        "start" to (start?.toString()?.padStart(5, '0') ?: ""),
                        "end" to (end?.toString()?.padStart(5, '0') ?: ""),
                    )
                }
                allClassrooms.add(roomInfo)
            }

            val totalSize = pageData["totalSize"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0
            if (pageNumber * pageSize >= totalSize) break
            pageNumber += 1
        }

        return allClassrooms
    }

    private fun parseJcStatus(jcStr: String?): String {
        if (jcStr.isNullOrBlank()) return "空闲"
        val occupations = mutableListOf<String>()
        for (status in jcStr.split(",")) {
            if (status.startsWith("1_")) {
                val code = status.substringAfter("_")
                occupations += STATUS_MAP[code] ?: "未知占用($code)"
            }
        }
        return if (occupations.isEmpty()) "空闲" else occupations.joinToString(" + ")
    }

    private suspend fun getCurrentTermInfo(): Map<String, Any?>? {
        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/kxjas/modules/kxjas/dqxnxqcx.do?vpn-12-o2-jxzxehallapp.bit.edu.cn"
        return try {
            val data = Json.parseToJsonElement(session.post(url, headers = Config.Headers.base).bodyText).jsonObject
            val rows = data["datas"]?.jsonObject?.get("dqxnxqcx")?.jsonObject?.get("rows")?.jsonArray
            rows?.firstOrNull()?.jsonObject?.let { row ->
                mapOf(
                    "DM" to row["DM"]?.jsonPrimitive?.content,
                    "XNDM" to row["XNDM"]?.jsonPrimitive?.content,
                    "XQDM" to row["XQDM"]?.jsonPrimitive?.content,
                )
            }
        } catch (_: Throwable) { null }
    }

    private suspend fun getWeekInfo(dateStr: String, xn: String?, xq: String?): Map<String, Any?>? {
        if (xn == null || xq == null) return null
        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/kxjas/modules/kxjas/rqzhzcjc.do?vpn-12-o2-jxzxehallapp.bit.edu.cn"
        return try {
            val resp = session.post(url, headers = Config.Headers.base, data = mapOf("RQ" to dateStr, "XN" to xn, "XQ" to xq))
            val data = Json.parseToJsonElement(resp.bodyText).jsonObject
            data["datas"]?.jsonObject?.get("rqzhzcjc")?.jsonObject?.let { obj ->
                mapOf(
                    "ZC" to obj["ZC"]?.jsonPrimitive?.contentOrNullSafe(),
                    "XQJ" to obj["XQJ"]?.jsonPrimitive?.contentOrNullSafe(),
                )
            }
        } catch (_: Throwable) { null }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = content
