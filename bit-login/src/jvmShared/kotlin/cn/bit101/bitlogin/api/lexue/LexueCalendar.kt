package cn.bit101.bitlogin.api.lexue

import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 乐学 (Moodle) 日历导出.
 */
class LexueCalendar(private val session: HttpClient) {

    data class CalendarEvent(
        val uid: String,
        val event: String,
        val description: String,
        val course: String,
        val time: LocalDateTime,
    )

    suspend fun getCalendarUrl(): String {
        val base = Config.Urls.active["lexue"] ?: throw RuntimeException("lexue 地址未配置")

        // 真实登录流程: 以 SSO service 页 {base}/login/index.php 为入口建立 Moodle 会话
        val indexHtml = establishMoodleSession(base)
        val sesskey = extractSesskey(indexHtml)
            ?: throw RuntimeException("获取乐学 sesskey 失败，可能是未登录或 Cookie 已过期")

        val exportHtml = session.post(
            "$base/calendar/export.php",
            data = mapOf(
                "sesskey" to sesskey,
                "_qf__core_calendar_export_form" to "1",
                "events[exportevents]" to "all",
                "period[timeperiod]" to "recentupcoming",
                "generateurl" to "获取日历网址",
            ),
        ).bodyText

        return extractCalendarUrl(exportHtml)
            ?: throw RuntimeException("获取日历订阅链接失败")
    }

    /**
     * 建立乐学 (Moodle) 会话，返回已登录首页 HTML。
     *
     * 真实流程: GET {base}/login/index.php (SSO service 页) 触发整条重定向链
     * `/login/index.php → login.bit.edu.cn/authserver → sso.bit.edu.cn CAS gateway(ticket)
     * → /login/index.php → /login/index.php?testsession → /`, 最后落在首页。
     * 链路自带 gateway=true, 只要会话内已有统一身份认证 Cookie 即静默重登。
     * 以 X-MOODLEUSER 响应头或首页 M.cfg.sesskey 校验登录成功。
     */
    private suspend fun establishMoodleSession(base: String): String {
        val home = session.get("$base/login/index.php")
        if (home.headers["X-MOODLEUSER"] == null && extractSesskey(home.bodyText) == null) {
            throw RuntimeException("乐学登录失败：统一身份认证会话未建立或已过期，请重新登录")
        }
        return home.bodyText
    }

    private fun extractSesskey(html: String): String? =
        Regex("[\"']sesskey[\"']:[\"']([^\"']+?)[\"']").find(html)?.groupValues?.get(1)

    private fun extractCalendarUrl(html: String): String? {
        // 真实页面为 <div class="generalbox calendarurl">日历网址： https://…&amp;…</div>，
        // URL 是纯文本（带 HTML 实体），不是 value/href 属性。
        val block = Regex("""class="[^"]*calendarurl[^"]*"[^>]*>([\s\S]*?)</""").find(html)?.groupValues?.get(1) ?: return null
        val url = Regex("""value="(https?://[^"]+)"""").find(block)?.groupValues?.get(1)
            ?: Regex("""href="(https?://[^"]+)"""").find(block)?.groupValues?.get(1)
            ?: block.indexOf("http").takeIf { it >= 0 }?.let { block.substring(it).trim() }
            ?: return null
        return unescapeHtml(url)
    }

    private fun unescapeHtml(value: String): String =
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&#39;", "'").replace("&amp;", "&")

    suspend fun getCalendar(url: String): List<CalendarEvent> {
        val ics = session.get(url).bodyText
        return parseIcs(ics)
    }

    suspend fun getCalendar(): List<CalendarEvent> = getCalendar(getCalendarUrl())

    private fun parseIcs(ics: String): List<CalendarEvent> {
        // 展开折叠行 (ICS 中一行超过 75 字符会折行, 续行以空格/制表符开头)
        val unfolded = ics.replace(Regex("\r?\n[ \t]"), "")
        val events = mutableListOf<CalendarEvent>()
        for (block in unfolded.split("BEGIN:VEVENT").drop(1)) {
            val vevent = block.substringBefore("END:VEVENT")
            fun prop(name: String): String =
                Regex("""^$name(?:;[^:]*)?:(.*)$""", RegexOption.MULTILINE)
                    .find(vevent)?.groupValues?.get(1)?.trim().orEmpty()

            events += CalendarEvent(
                uid = prop("UID"),
                event = unescape(prop("SUMMARY")),
                description = unescape(prop("DESCRIPTION")),
                course = unescape(prop("CATEGORIES")),
                time = parseDtStart(prop("DTSTART")),
            )
        }
        return events
    }

    private fun parseDtStart(value: String): LocalDateTime {
        val digits = value.filter { it.isDigit() }
        val parsed = LocalDateTime.parse(digits.take(14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        // UTC 时间戳 (以 Z 结尾) 转为东八区本地时间
        return if (value.endsWith("Z")) parsed.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime()
        else parsed
    }

    private fun unescape(value: String): String =
        value.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
