package cn.bit101.bitlogin.api.lexue

import cn.bit101.bitlogin.Config
import cn.bit101.bitlogin.http.HttpClient
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 乐学 (Moodle) 日历导出.
 *
 * 调用前需先通过登录流程建立乐学会话（持有 lexue 域名的 MoodleSession Cookie），
 * 否则首页请求会经统一身份认证重定向后停留在登录页，无法解析出 sesskey。
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
        val base = (Config.Urls.active["lexue"] ?: throw RuntimeException("未配置乐学地址")).trimEnd('/')

        val indexHtml = session.get("$base/", headers = htmlHeaders).bodyText
        val sesskey = parseSesskey(indexHtml)
            ?: throw RuntimeException("获取乐学 sesskey 失败，可能是未登录或 Cookie 已过期")

        val exportHtml = session.post(
            "$base/calendar/export.php",
            headers = htmlHeaders,
            data = mapOf(
                "sesskey" to sesskey,
                "_qf__core_calendar_export_form" to "1",
                "events[exportevents]" to "all",
                "period[timeperiod]" to "recentupcoming",
                "generateurl" to "获取日历网址",
            ),
        ).bodyText

        return parseCalendarUrl(exportHtml)
            ?: throw RuntimeException("获取日历订阅链接失败")
    }

    /**
     * 从乐学首页 HTML 中提取 Moodle sesskey。Moodle 可能以多种形式输出：
     * 1. `M.cfg = {...}` 中的 JSON 键值：`"sesskey":"xxx"`
     * 2. 表单隐藏字段：`<input type="hidden" name="sesskey" value="xxx">`
     * 3. 旧的 JS 赋值：`M.cfg.sesskey = "xxx"`
     */
    internal fun parseSesskey(html: String): String? {
        val patterns = listOf(
            Regex("""["']sesskey["']\s*:\s*["']([^"']+?)["']"""),
            Regex("""name\s*=\s*["']sesskey["'][^>]*?value\s*=\s*["']([^"']+?)["']"""),
            Regex("""value\s*=\s*["']([^"']+?)["'][^>]*?name\s*=\s*["']sesskey["']"""),
            Regex("""M\.cfg\.sesskey\s*=\s*["']([^"']+?)["']"""),
        )
        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * 从 export.php 返回的 HTML 中解析日历订阅链接。
     * Moodle 将链接放在 `.calendarurl` 容器内的 `#calendarexporturl` 输入框的 value 中。
     */
    internal fun parseCalendarUrl(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.select(".calendarurl").firstOrNull()?.let { container ->
            container.selectFirst("input")?.attr("value")?.trim()
                ?.takeIf { it.startsWith("http") }
                ?.let { return it }
            container.selectFirst("a")?.attr("href")?.trim()
                ?.takeIf { it.startsWith("http") }
                ?.let { return it }
            extractFirstHttp(container.text())?.let { return it }
        }
        doc.selectFirst("#calendarexporturl")?.attr("value")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }
        return extractFirstHttp(doc.text())
    }

    private fun extractFirstHttp(text: String): String? {
        val start = text.indexOf("http")
        if (start < 0) return null
        val url = text.substring(start)
        // 只取到第一个空白字符, 避免带上后面的 HTML 文本
        return url.takeWhile { !it.isWhitespace() }.ifBlank { null }
    }

    private val htmlHeaders: Map<String, String> = Config.Headers.jwb

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
