package cn.bit101.bitlogin.api.jwb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConvertChineseScoreTest {

    @Test
    fun `maps Chinese grades to numeric`() {
        assertEquals("95", convertChineseScore("优秀"))
        assertEquals("85", convertChineseScore("良好"))
        assertEquals("75", convertChineseScore("中等"))
        assertEquals("65", convertChineseScore("及格"))
        assertEquals("0", convertChineseScore("不及格"))
    }

    @Test
    fun `passes through numeric scores`() {
        assertEquals("92", convertChineseScore("92"))
        assertEquals("", convertChineseScore(""))
    }
}
