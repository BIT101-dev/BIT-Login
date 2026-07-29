package cn.bit101.bitlogin.api.jxzxehall

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConstantsTest {

    @Test
    fun `TIME_TABLE has 13 entries`() {
        assertEquals(13, TIME_TABLE.size)
        // Spot-check the first/last.
        assertEquals(8, TIME_TABLE.first().first.hour)
        assertEquals(20, TIME_TABLE.last().second.hour)
    }

    @Test
    fun `BUILDING_MAP contains known buildings`() {
        assertNotNull(BUILDING_MAP["综教A"])
        assertNotNull(BUILDING_MAP["文萃楼M"])
        assertEquals(39.733193, BUILDING_MAP["综教A"]!!.first)
    }

    @Test
    fun `getBuildingCoord matches by substring, preferring longest key`() {
        // 综教A matches literally
        val a = getBuildingCoord("综教A101")
        assertNotNull(a)
        assertEquals(BUILDING_MAP["综教A"], a)
        // Longest-prefix preference: 综教A should not shadow 综教B
        val b = getBuildingCoord("综教B201")
        assertEquals(BUILDING_MAP["综教B"], b)
    }

    @Test
    fun `getBuildingCoord returns null for unknown`() {
        assertNull(getBuildingCoord("未知地点"))
        assertNull(getBuildingCoord(null))
        assertNull(getBuildingCoord(""))
    }

    @Test
    fun `STATUS_MAP covers all Python entries`() {
        assertEquals("排课占用", STATUS_MAP["01"])
        assertEquals("排课占用(特定)", STATUS_MAP["10"])
        assertEquals("特殊排课", STATUS_MAP["11"])
        assertEquals(7, STATUS_MAP.size)
    }
}
