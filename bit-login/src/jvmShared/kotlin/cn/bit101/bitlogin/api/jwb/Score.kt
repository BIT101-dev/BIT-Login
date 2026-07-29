package cn.bit101.bitlogin.api.jwb

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.api.jxzxehall.currentKksj
import cn.bit101.bitlogin.http.HttpClient
import java.net.URI

/**
 * 教务系统成绩查询. Mirrors Python `services/jwb.py:score`.
 */
class Score(
    private val session: HttpClient,
) {
    private fun scoreListUrl(): String = "${Config.Urls.active["jwb_cb"]}jsxsd/kscj/cjcx_list"

    private fun scoreRequestHeaders(): Map<String, String> {
        val cb = Config.Urls.active["jwb_cb"] ?: ""
        val parsed = URI(cb)
        val origin = "${parsed.scheme}://${parsed.rawAuthority}"
        return mapOf("Origin" to origin)
    }

    private suspend fun postScoreList(data: Map<String, String>): cn.bit101.bitlogin.http.HttpResponse {
        val response = session.post(scoreListUrl(), data = data, headers = scoreRequestHeaders())
        if (response.status >= 500) throw RuntimeException("成绩列表获取失败: HTTP ${response.status}")
        return response
    }

    suspend fun getScore(kksj: String? = null, detailed: Boolean = false): List<Map<String, Any?>> {
        val effectiveKksj = kksj ?: currentKksj()
        val data = mapOf(
            "kksj" to effectiveKksj,
            "kcxz" to "",
            "kcmc" to "",
            "xsfs" to "all",
        )
        val response = postScoreList(data)
        return parseScore(response.bodyText, detailed)
    }

    suspend fun getScoreDetail(url: String): Map<String, Any?> {
        val response = session.get(url)
        return parseScoreDetail(response.bodyText)
    }

    fun parseScore(html: String, detailed: Boolean = false): List<Map<String, Any?>> {
        val doc: Document = Jsoup.parse(html)
        val dataList = doc.getElementById("dataList") ?: return emptyList()
        val rows = dataList.select("> tbody > tr, > tr")
        val nameEl = doc.getElementById("Top1_divLoginName") ?: return emptyList()
        val studentName = nameEl.text()

        val res = mutableListOf<Map<String, Any?>>()
        for (row in rows.drop(1)) {  // skip header
            val cells = row.select("td")
            if (cells.size < 12) continue
            val entry: MutableMap<String, Any?> = LinkedHashMap()
            entry["student"] = studentName
            entry["course"] = cells[3].text()
            entry["score"] = convertChineseScore(cells[4].text())
            entry["credit"] = cells[6].text()
            entry["hours"] = cells[7].text()
            entry["kksj"] = cells[1].text()
            entry["type"] = cells[11].text()

            val onclick = cells.lastOrNull()?.selectFirst("a")?.attr("onclick")
            if (detailed && onclick != null && "JsMod('" in onclick) {
                val detailRelPath = onclick.substringAfter("JsMod('").substringBefore("'").substring(1)
                val detailUrl = Config.Urls.active["jwb_cb"] + detailRelPath
                kotlinx.coroutines.runBlocking { entry.putAll(getScoreDetail(detailUrl)) }
            } else {
                entry.putAll(emptyDetailFields())
            }
            res.add(entry)
        }
        return res
    }

    fun parseScoreDetail(html: String): Map<String, Any?> {
        val doc = Jsoup.parse(html)
        val dataLists = doc.select("#dataList")
        if (dataLists.size < 3) return emptyDetailFields()

        val table2Rows = dataLists[1].select("tr")
        if (table2Rows.size < 2) return emptyDetailFields()
        val row1 = table2Rows[0].select("td")
        val row2 = table2Rows[1].select("td")
        val table3 = dataLists[2].select("td")

        fun splitColon(text: String): String = text.substringAfterLast("：").trim()
        return linkedMapOf(
            "class_number" to (row1.getOrNull(0)?.let { splitColon(it.text()) }),
            "major_number" to (row1.getOrNull(1)?.let { splitColon(it.text()) }),
            "study_number" to (row1.getOrNull(2)?.let { splitColon(it.text()) }),
            "average" to (row2.getOrNull(0)?.let { splitColon(it.text()) }),
            "max" to (row2.getOrNull(1)?.let { splitColon(it.text()) }),
            "entry_complete" to (row2.getOrNull(2)?.let { splitColon(it.text()) }),
            "self_score" to (table3.getOrNull(0)?.let { splitColon(it.text()) }),
            "class_proportion" to (table3.getOrNull(1)?.let { splitColon(it.text()) }),
            "major_proportion" to (table3.getOrNull(2)?.let { splitColon(it.text()) }),
            "school_proportion" to (table3.getOrNull(3)?.let { splitColon(it.text()) }),
        )
    }

    suspend fun getAllScore(detailed: Boolean = false): List<Map<String, Any?>> =
        getScore("", detailed).let { if (it.isEmpty()) emptyList() else it }

    suspend fun getBit101Score(kksj: String? = null, detailed: Boolean = false): List<List<String>> {
        val data = mapOf(
            "kksj" to (kksj ?: ""),
            "kcxz" to "",
            "kcmc" to "",
            "xsfs" to "all",
        )
        val response = postScoreList(data)
        return parseBit101Score(response.bodyText, detailed)
    }

    fun parseBit101Score(html: String, detailed: Boolean = false): List<List<String>> {
        val doc = Jsoup.parse(html)
        val dataList = doc.getElementById("dataList") ?: return emptyList()
        val rows = dataList.select("> tbody > tr, > tr")
        if (rows.isEmpty()) return emptyList()

        val baseHeader = listOf(
            "序号", "开课学期", "课程编号", "课程名称", "成绩", "成绩标识",
            "学分", "总学时", "考试性质", "考核方式", "课程属性", "课程性质",
            "课程归属", "课程种类", "是否第一次考试", "操作栏",
        )
        val detailHeader = listOf(
            "专业人数", "学习人数", "平均分", "本人成绩", "班级人数", "最高分",
            "该课程所有教学班成绩录入完毕", "本人成绩在班级中占", "本人成绩在专业中占", "本人成绩在所有学生中占",
        )
        val header = if (detailed) baseHeader + detailHeader else baseHeader
        val res = mutableListOf<List<String>>(header)

        for (row in rows.drop(1)) {
            val tds = row.select("td")
            if (tds.isEmpty()) continue
            var rowValues = tds.map { it.text().trim() }
            while (rowValues.size < 16) rowValues = rowValues + ""
            rowValues = rowValues.take(16).toMutableList()

            // Convert Chinese score (index 4).
            rowValues[4] = convertChineseScore(rowValues[4])

            if (detailed) {
                val detailList = MutableList(10) { "" }
                val aTag = tds.lastOrNull()?.selectFirst("a")
                val onclick = aTag?.attr("onclick")
                if (aTag != null && onclick != null && "JsMod('" in onclick) {
                    try {
                        val rel = onclick.substringAfter("JsMod('").substringBefore("'").substring(1)
                        val detailUrl = Config.Urls.active["jwb_cb"] + rel
                        val detail = kotlinx.coroutines.runBlocking { getScoreDetail(detailUrl) }
                        detailList[0] = detail["major_number"]?.toString() ?: ""
                        detailList[1] = detail["study_number"]?.toString() ?: ""
                        detailList[2] = detail["average"]?.toString() ?: ""
                        detailList[3] = detail["self_score"]?.toString() ?: ""
                        detailList[4] = detail["class_number"]?.toString() ?: ""
                        detailList[5] = detail["max"]?.toString() ?: ""
                        detailList[6] = detail["entry_complete"]?.toString() ?: ""
                        detailList[7] = detail["class_proportion"]?.toString() ?: ""
                        detailList[8] = detail["major_proportion"]?.toString() ?: ""
                        detailList[9] = detail["school_proportion"]?.toString() ?: ""
                    } catch (_: Throwable) {}
                }
                res.add(rowValues + detailList)
            } else {
                res.add(rowValues)
            }
        }
        return res
    }

    suspend fun getAllBit101Score(detailed: Boolean = false): List<List<String>> =
        getBit101Score("", detailed).let { if (it.isEmpty()) emptyList() else it }
}

/** Mirrors the inline 优秀→95 etc mapping in Python `services/jwb.py`. */
fun convertChineseScore(score: String): String = when (score) {
    "优秀" -> "95"
    "良好" -> "85"
    "中等" -> "75"
    "及格" -> "65"
    "不及格" -> "0"
    else -> score
}

private fun emptyDetailFields(): Map<String, Any?> = linkedMapOf(
    "class_number" to null,
    "major_number" to null,
    "study_number" to null,
    "average" to null,
    "max" to null,
    "entry_complete" to null,
    "self_score" to null,
    "class_proportion" to null,
    "major_proportion" to null,
    "school_proportion" to null,
)
