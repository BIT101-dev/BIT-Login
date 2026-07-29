package cn.bit101.bitlogin.api.jwb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoreParserTest {

    private val sampleHtml = """
        <html><body>
        <div id="Top1_divLoginName">张三</div>
        <table id="dataList">
          <tr><th>序号</th><th>开课学期</th><th>课程编号</th><th>课程名称</th><th>成绩</th><th>成绩标识</th><th>学分</th><th>总学时</th><th>考试性质</th><th>考核方式</th><th>课程属性</th><th>课程性质</th><th>课程归属</th><th>课程种类</th><th>是否第一次考试</th><th>操作栏</th></tr>
          <tr>
            <td>1</td><td>2023-2024-1</td><td>CS101</td><td>数据结构</td><td>优秀</td><td></td><td>3.0</td><td>48</td><td>正常</td><td>考试</td><td>必修</td><td>专业课</td><td></td><td></td><td>是</td>
            <td><a href="javascript:void(0)" onclick="JsMod('/cjcx_detail?id=1')">详情</a></td>
          </tr>
          <tr>
            <td>2</td><td>2023-2024-1</td><td>MA101</td><td>高等数学</td><td>85</td><td></td><td>4.0</td><td>64</td><td>正常</td><td>考试</td><td>必修</td><td>公共课</td><td></td><td></td><td>是</td>
            <td></td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `parseScore extracts student name and rows`() {
        val score = Score(session = cn.bit101.bitlogin.http.HttpClient())
        val parsed = score.parseScore(sampleHtml, detailed = false)
        assertEquals(2, parsed.size)
        assertEquals("张三", parsed[0]["student"])
    }

    @Test
    fun `parseScore converts Chinese grades`() {
        val score = Score(session = cn.bit101.bitlogin.http.HttpClient())
        val parsed = score.parseScore(sampleHtml, detailed = false)
        assertEquals("95", parsed[0]["score"])  // 优秀 → 95
        assertEquals("85", parsed[1]["score"])  // passes through
    }

    @Test
    fun `parseScore without detailed populates empty detail fields`() {
        val score = Score(session = cn.bit101.bitlogin.http.HttpClient())
        val parsed = score.parseScore(sampleHtml, detailed = false)
        // Should have all detail fields (as nulls when detailed=false)
        assertTrue(parsed[0].containsKey("class_number"))
        assertEquals(null, parsed[0]["class_number"])
        assertEquals(null, parsed[0]["average"])
    }

    @Test
    fun `parseBit101Score returns header + rows`() {
        val score = Score(session = cn.bit101.bitlogin.http.HttpClient())
        val parsed = score.parseBit101Score(sampleHtml, detailed = false)
        assertEquals(3, parsed.size)  // header + 2 rows
        assertEquals(16, parsed[0].size)  // base header has 16 columns
        assertEquals("序号", parsed[0][0])
        assertEquals("课程名称", parsed[0][3])
    }

    @Test
    fun `parseBit101Score with detailed appends detail header`() {
        val score = Score(session = cn.bit101.bitlogin.http.HttpClient())
        val parsed = score.parseBit101Score(sampleHtml, detailed = true)
        assertEquals(16 + 10, parsed[0].size)  // base + detail header
        assertEquals("专业人数", parsed[0][16])
        assertEquals("本人成绩在所有学生中占", parsed[0][25])
    }

    @Test
    fun `parseBit101Score converts Chinese score at index 4`() {
        val score = Score(session = cn.bit101.bitlogin.http.HttpClient())
        val parsed = score.parseBit101Score(sampleHtml, detailed = false)
        // Row 0: 优秀 → 95
        assertEquals("95", parsed[1][4])
        // Row 1: 85 passes through
        assertEquals("85", parsed[2][4])
    }
}
