package cn.bit101.bitlogin.api.jxzxehall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient

/**
 * 教学中心/一站式大厅 - 学期与校历查询.
 */
class Term(private val session: HttpClient) {

    suspend fun getCurrentTerm(): String {
        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do"
        val res = responseJson(session.get(url, headers = Config.Headers.base), "当前学期")
        return try {
            res["datas"]!!.jsonObject["dqxnxq"]!!.jsonObject["rows"]!!.jsonArray[0].jsonObject["DM"]!!.jsonPrimitive.content
        } catch (e: Throwable) {
            throw JxzxehallDataError("教学中心未返回当前学期；该账号可能不使用本科教学中心")
        }
    }

    suspend fun getTermList(): List<String> {
        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdkbby/modules/jshkcb/xnxqcx.do"
        val res = responseJson(session.get(url, headers = Config.Headers.base), "学期列表")
        val rows = try {
            res["datas"]!!.jsonObject["xnxqcx"]!!.jsonObject["rows"]!!.jsonArray
        } catch (e: Throwable) {
            throw JxzxehallDataError("教学中心未返回学期列表")
        }
        return rows.map { it.jsonObject["DM"]!!.jsonPrimitive.content }
    }

    suspend fun getWeekAndDate(term: String, week: Int = 1): List<Map<String, String>> {
        val payload = Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(),
            mapOf("XNXQDM" to term, "ZC" to week.toString()),
        )
        val res = responseJson(
            session.post(
                "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/wdkbby/wdkbByController/cxzkbrq.do",
                headers = Config.Headers.base,
                data = mapOf("requestParamStr" to payload),
            ),
            "校历",
        )
        val data = res["data"]?.jsonArray ?: throw JxzxehallDataError("教学中心未返回校历数据")
        return data.map {
            val obj = it.jsonObject
            mapOf(
                "dayOfWeek" to (obj["XQ"]?.jsonPrimitive?.content ?: ""),
                "date" to (obj["RQ"]?.jsonPrimitive?.content ?: ""),
            )
        }
    }
}
