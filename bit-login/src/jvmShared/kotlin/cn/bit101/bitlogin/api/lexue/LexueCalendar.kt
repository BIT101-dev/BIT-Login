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
        val base = Config.Urls.active["lexue"]

        val indexHtml = session.get("$base/").bodyText
        val sesskey = Regex("[\"']sesskey[\"']:[\"']([^\"']+?)[\"']").find(indexHtml)?.groupValues?.get(1)
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

        // 通过 calendarurl class 查找日历订阅链接
        val div = Regex("""class="calendarurl"[^>]*>([\s\S]*?)</""").find(exportHtml)?.groupValues?.get(1)
            ?: throw RuntimeException("获取日历订阅链接失败")
        return div.substring(div.indexOf("http")).trim()
    }

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
