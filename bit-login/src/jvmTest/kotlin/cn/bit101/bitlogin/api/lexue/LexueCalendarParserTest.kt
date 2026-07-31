package cn.bit101.bitlogin.api.lexue

import cn.bit101.bitlogin.http.HttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LexueCalendarParserTest {

    private val calendar = LexueCalendar(session = HttpClient())

    @Test
    fun `parseSesskey extracts from cfg JSON`() {
        val html = """
            <script>
            M.cfg = {"wwwroot":"https:\/\/lexue.bit.edu.cn","sesskey":"aBcDeF123","sessiontimeout":"28800"};
            </script>
        """.trimIndent()
        assertEquals("aBcDeF123", calendar.parseSesskey(html))
    }

    @Test
    fun `parseSesskey extracts from hidden form input`() {
        val html = """
            <form>
            <input type="hidden" name="sesskey" value="XyZ987" />
            </form>
        """.trimIndent()
        assertEquals("XyZ987", calendar.parseSesskey(html))
    }

    @Test
    fun `parseSesskey extracts when value attribute precedes name`() {
        val html = """<input type="hidden" value="qwe456" name="sesskey" />"""
        assertEquals("qwe456", calendar.parseSesskey(html))
    }

    @Test
    fun `parseSesskey extracts from cfg dot notation`() {
        val html = """<script>M.cfg.sesskey = "dotNotation789";</script>"""
        assertEquals("dotNotation789", calendar.parseSesskey(html))
    }

    @Test
    fun `parseSesskey returns null on login page`() {
        val html = """
            <html><title>统一身份认证平台</title><form id="normalLoginForm"></form></html>
        """.trimIndent()
        assertNull(calendar.parseSesskey(html))
    }

    @Test
    fun `parseCalendarUrl extracts input value in calendarurl container`() {
        val html = """
            <div class="generalbox calendarurl mt-3">
              <div class="card">
                <div class="card-body alert-info">
                  <div class="input-group">
                    <input type="text" id="calendarexporturl" class="form-control"
                           value="https://lexue.bit.edu.cn/calendar/export_execute.php?userid=42&amp;authtoken=abc123&amp;preset_what=all&amp;preset_time=recentupcoming"
                           readonly />
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()
        val url = calendar.parseCalendarUrl(html)
        assertEquals(
            "https://lexue.bit.edu.cn/calendar/export_execute.php?userid=42&authtoken=abc123&preset_what=all&preset_time=recentupcoming",
            url,
        )
    }

    @Test
    fun `parseCalendarUrl falls back to anchor href`() {
        val html = """
            <div class="calendarurl"><a href="https://lexue.bit.edu.cn/calendar/export_execute.php?userid=1&amp;authtoken=xyz">订阅</a></div>
        """.trimIndent()
        assertEquals("https://lexue.bit.edu.cn/calendar/export_execute.php?userid=1&authtoken=xyz", calendar.parseCalendarUrl(html))
    }

    @Test
    fun `parseCalendarUrl returns null without url`() {
        assertNull(calendar.parseCalendarUrl("<html><body>no export url here</body></html>"))
    }
}
