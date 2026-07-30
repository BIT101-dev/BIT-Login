package cn.bit101.bitlogin.api.jxzxehall

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient

/**
 * 空教室查询 - 校区与教学楼字典.
 */
class Campus(private val session: HttpClient) {

    suspend fun getCampusList(): List<Map<String, String>> {
        initAppSession()
        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/kxjasbyMobile/modules/jxllb/ggzdpx.do"
        val response = session.request(
            HttpMethod.Get,
            url,
            headers = Config.Headers.base,
            query = mapOf(
                // 不明来源, 不明意义, 但似乎不会变而且不加不行
                "dicCode" to "48682",
                "SFSY" to "1",
                "order" to "+DM",
            ),
        )
        if (response.status != 200) {
            throw RuntimeException("获取校区列表失败，HTTP 状态码: ${response.status}, Body: ${response.bodyText.take(800)}")
        }
        val data = Json.parseToJsonElement(response.bodyText).jsonObject
        val rows = data["datas"]?.jsonObject?.get("ggzdpx")?.jsonObject?.get("rows")?.jsonArray
            ?: throw RuntimeException("获取校区列表失败，可能是 Cookie 已过期")
        return rows.map { row ->
            val obj = row.jsonObject
            mapOf(
                "name" to (obj["MC"]?.jsonPrimitive?.content ?: ""),
                "code" to (obj["DM"]?.jsonPrimitive?.content ?: ""),
            )
        }
    }

    suspend fun getBuildingList(campusCode: String? = null): List<Map<String, String>> {
        initAppSession()
        val url = "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/kxjasbyMobile/modules/jxllb/cxjxl.do"
        // campusCode 为 null 则不添加参数, 此时返回全部教学楼
        val query = if (campusCode != null) mapOf("XXXQDM" to campusCode) else emptyMap()
        val response = session.request(HttpMethod.Get, url, headers = Config.Headers.base, query = query)
        if (response.status != 200) {
            throw RuntimeException("获取教学楼列表失败，HTTP 状态码: ${response.status}, Body: ${response.bodyText.take(800)}")
        }
        val data = Json.parseToJsonElement(response.bodyText).jsonObject
        val rows = data["datas"]?.jsonObject?.get("cxjxl")?.jsonObject?.get("rows")?.jsonArray
            ?: throw RuntimeException("获取教学楼列表失败，可能是 Cookie 已过期")
        return rows.map { row ->
            val obj = row.jsonObject
            mapOf(
                "name" to (obj["JXLMC"]?.jsonPrimitive?.content ?: ""),
                "code" to (obj["JXLDM"]?.jsonPrimitive?.content ?: ""),
                "campus_name" to (obj["XXXQDM_DISPLAY"]?.jsonPrimitive?.content ?: ""),
                "campus_code" to (obj["XXXQDM"]?.jsonPrimitive?.content ?: ""),
            )
        }
    }

    // 先访问应用首页以初始化应用会话, 否则后端可能返回异常
    private suspend fun initAppSession() {
        session.get(
            "${Config.Urls.active["jxzxehall_app"]}/jwapp/sys/kxjasbyMobile/*default/index.do",
            headers = Config.Headers.jxzxehall,
        )
    }
}
