package cn.bit101.bitlogin.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JxzxehallLoginTest {

    @Test
    fun `graduate sids land on wdkbby`() {
        assertEquals("jxzxehall_wdkbby_", landingAppPrefix("3122101001")) // 学术型硕士
        assertEquals("jxzxehall_wdkbby_", landingAppPrefix("3222101001")) // 专业学位硕士
    }

    @Test
    fun `undergraduate sids land on xsfacx`() {
        assertEquals("jxzxehall_", landingAppPrefix("1122101001")) // 本科生
        assertEquals("jxzxehall_", landingAppPrefix("1322101001")) // 国内交换生
        assertEquals("jxzxehall_", landingAppPrefix("1822101001")) // 留学本科生
    }

    @Test
    fun `malformed or unknown sids fall back to xsfacx`() {
        assertEquals("jxzxehall_", landingAppPrefix(""))
        assertEquals("jxzxehall_", landingAppPrefix("31"))
        assertEquals("jxzxehall_", landingAppPrefix("3122101001x")) // 11位
        assertEquals("jxzxehall_", landingAppPrefix("31a2101001")) // 10位含非数字
        assertEquals("jxzxehall_", landingAppPrefix("4122101001")) // 未知前缀
    }
}
