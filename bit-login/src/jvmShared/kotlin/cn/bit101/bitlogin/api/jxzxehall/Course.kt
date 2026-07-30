package cn.bit101.bitlogin.api.jxzxehall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 课程表查询 + ICS 生成. Mirrors Python `services/jxzxehall.py:course`.
 */
class Course(private val session: HttpClient) {

    suspend fun getCourses(kksj: String? = null): Map<String, Any?> {
        var term = kksj
        if (term.isNullOrBlank()) {
            val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do"
            val res = responseJson(session.get(url, headers = Config.Headers.base), "当前学期")
            term = try {
                res["datas"]!!.jsonObject["dqxnxq"]!!.jsonObject["rows"]!!.jsonArray[0].jsonObject["DM"]!!.jsonPrimitive.content
            } catch (e: Throwable) {
                throw JxzxehallDataError("教学中心未返回当前学期；该账号可能不使用本科教学中心")
            }
        }

        val firstDayPayload = Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(),
            mapOf("XNXQDM" to term, "ZC" to "1"),
        )
        val firstDayRes = responseJson(
            session.post(
                "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdkbby/wdkbByController/cxzkbrq.do",
                headers = Config.Headers.base,
                data = mapOf("requestParamStr" to firstDayPayload),
            ),
            "校历",
        )

        val firstDay = firstDayRes["data"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["XQ"]?.jsonPrimitive?.contentOrNullSafe() == "1" }
            ?.get("RQ")?.jsonPrimitive?.content.orEmpty()

        val scheduleRes = responseJson(
            session.post(
                "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdkbby/modules/xskcb/cxxszhxqkb.do?vpn-12-o2-jxzxehallapp.bit.edu.cn",
                headers = Config.Headers.base,
                data = mapOf("XNXQDM" to term),
            ),
            "课程表",
        )
        val rows = try {
            scheduleRes["datas"]!!.jsonObject["cxxszhxqkb"]!!.jsonObject["rows"]!!.jsonArray
        } catch (e: Throwable) {
            throw JxzxehallDataError("教学中心未返回本科课程表")
        }

        return mapOf(
            "term" to term,
            "firstDay" to firstDay,
            "data" to rows,
        )
    }

    suspend fun generateIcs(kksj: String? = null): Pair<String, String> {
        val schedule = getCourses(kksj)
        val firstDay = try {
            LocalDate.parse(
                schedule["firstDay"] as String,
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            ).atStartOfDay()
        } catch (e: Throwable) {
            throw JxzxehallDataError("教学中心未返回有效的学期开始日期")
        }
        var classCount = 0
        var timeCount = 0

        val calendar = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:BIT101 ${LocalDateTime.now()}",
            "TZID:Asia/Shanghai",
            "X-WR-CALNAME:BIT101课程表",
        )

        @Suppress("UNCHECKED_CAST")
        val rows = (schedule["data"] as JsonArray)
        for (course in rows.map { it.jsonObject }) {
            val skzc = course["SKZC"]?.jsonPrimitive?.content ?: ""
            for ((weekIndex, ch) in skzc.withIndex()) {
                if (ch != '1') continue
                val ksjc = course["KSJC"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 1
                val jsjc = course["JSJC"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 1
                val skxq = course["SKXQ"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 1
                val startHm = TIME_TABLE[ksjc - 1].first
                val endHm = TIME_TABLE[jsjc - 1].second
                val daysAdd = weekIndex * 7 + skxq - 1

                val startDt = firstDay.plusDays(daysAdd.toLong())
                    .withHour(startHm.hour).withMinute(startHm.minute)
                val endDt = firstDay.plusDays(daysAdd.toLong())
                    .withHour(endHm.hour).withMinute(endHm.minute)

                val jasmc = course["JASMC"]?.jsonPrimitive?.content ?: ""
                val kcm = course["KCM"]?.jsonPrimitive?.content ?: ""
                val skjs = course["SKJS"]?.jsonPrimitive?.content ?: ""
                val ypsjdd = course["YPSJDD"]?.jsonPrimitive?.content ?: ""

                calendar.add("BEGIN:VEVENT")
                calendar.add("SUMMARY:$kcm")
                calendar.add("LOCATION:$jasmc\\n北京理工大学")
                getBuildingCoord(jasmc)?.let { (lat, lon) ->
                    calendar.add(
                        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"北京理工大学\";X-TITLE=\"$jasmc\":geo:${"%.6f".format(Locale.ROOT, lat)},${"%.6f".format(Locale.ROOT, lon)}",
                    )
                }
                calendar.add("DESCRIPTION:$skjs | $ypsjdd")
                calendar.add("DTSTART;TZID=Asia/Shanghai:${startDt.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))}")
                calendar.add("DTEND;TZID=Asia/Shanghai:${endDt.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))}")
                calendar.add("UID:${UUID.randomUUID()}")
                calendar.add("END:VEVENT")

                classCount += 1
                timeCount += (jsjc - ksjc + 1) * 45
            }
        }

        calendar.add("END:VCALENDAR\n")
        val icsContent = calendar.joinToString("\n")
        val note = "一共添加了${schedule["term"]}学期的${classCount}节课，合计坐牢时间${"%.1f".format(Locale.ROOT, timeCount / 60.0)}小时（雾"
        return icsContent to note
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = content
