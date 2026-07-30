package cn.bit101.bitlogin.api.jxzxehall

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient

/**
 * 教学中心/一站式大厅 - 考试安排查询.
 */
class Exam(private val session: HttpClient) {

    suspend fun getExamList(term: String): List<Map<String, String?>> {
        // 先访问应用首页以初始化应用会话, 否则后端可能返回异常
        session.get(
            "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdksapMobile/*default/index.do",
            headers = Config.Headers.jxzxehall,
        )

        val res = responseJson(
            session.post(
                "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdksapMobile/modules/ksap/cxxsksap.do",
                headers = Config.Headers.base,
                data = mapOf("XNXQDM" to term, "*order" to "-KSRQ"),
            ),
            "考试安排",
        )
        val rows = try {
            res["datas"]!!.jsonObject["cxxsksap"]!!.jsonObject["rows"]!!.jsonArray
        } catch (e: Throwable) {
            throw JxzxehallDataError("教学中心未返回考试安排")
        }
        return rows.map {
            val obj = it.jsonObject
            mapOf(
                "location" to obj["JASMC"]?.jsonPrimitive?.content,
                "time" to obj["KSSJMS"]?.jsonPrimitive?.content,
                "date" to obj["KSRQ"]?.jsonPrimitive?.content,
                "seat_id" to obj["ZWH"]?.jsonPrimitive?.content,
                "ksmc" to obj["KSMC"]?.jsonPrimitive?.content,
                "term_code" to obj["XNXQDM_DISPLAY"]?.jsonPrimitive?.content,
                "course" to obj["KCM"]?.jsonPrimitive?.content,
                "teacher" to obj["ZJJSXM"]?.jsonPrimitive?.content,
                "kch" to obj["KCH"]?.jsonPrimitive?.content,
            )
        }
    }
}
