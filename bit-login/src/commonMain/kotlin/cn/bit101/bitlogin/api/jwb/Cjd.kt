package cn.bit101.bitlogin.api.jwb

import cn.bit101.bitlogin.http.HttpClient

/**
 * 教务系统成绩单 (CJD). Mirrors Python `services/jwb.py:cjd`.
 *
 * Returns the URL of the generated transcript image.
 */
class Cjd(
    private val session: HttpClient,
) {
    suspend fun getCjd(gpa: Boolean = true): String {
        val requireGpa = if (gpa) 1 else 0
        val res = session.get("https://jwb.bit.edu.cn/cjd/ScoreReport2/Index?GPA=$requireGpa").bodyText
        check("以下显示的是本次申请的成绩信息" in res) { "成绩单获取失败!" }
        val raw = res.substringAfter("<img src=\"/cjd/Temp/", "")
            .substringBefore("\" class=\"img-fluid w-100\" a", "")
        check(raw.isNotEmpty()) { "成绩单获取失败!" }
        return "https://jwb.bit.edu.cn/cjd/Temp/$raw"
    }
}
