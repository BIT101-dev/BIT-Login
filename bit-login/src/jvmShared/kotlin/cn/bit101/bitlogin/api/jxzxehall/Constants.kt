package cn.bit101.bitlogin.api.jxzxehall

import java.time.LocalDateTime
import java.time.LocalTime

/** Mirrors Python `services/jxzxehall.py:TIME_TABLE` (13 节课). */
internal val TIME_TABLE: List<Pair<LocalTime, LocalTime>> = listOf(
    LocalTime.of(8, 0) to LocalTime.of(8, 45),
    LocalTime.of(8, 50) to LocalTime.of(9, 35),
    LocalTime.of(9, 55) to LocalTime.of(10, 40),
    LocalTime.of(10, 45) to LocalTime.of(11, 30),
    LocalTime.of(11, 35) to LocalTime.of(12, 20),
    LocalTime.of(13, 20) to LocalTime.of(14, 5),
    LocalTime.of(14, 10) to LocalTime.of(14, 55),
    LocalTime.of(15, 15) to LocalTime.of(16, 0),
    LocalTime.of(16, 5) to LocalTime.of(16, 50),
    LocalTime.of(16, 55) to LocalTime.of(17, 40),
    LocalTime.of(18, 30) to LocalTime.of(19, 15),
    LocalTime.of(19, 20) to LocalTime.of(20, 5),
    LocalTime.of(20, 10) to LocalTime.of(20, 55),
)

/** Mirrors Python `services/jxzxehall.py:BUILDING_MAP`. */
internal val BUILDING_MAP: Map<String, Pair<Double, Double>> = mapOf(
    "综教A" to Pair(39.733193, 116.170654),
    "综教B" to Pair(39.733184, 116.171878),
    "理教楼" to Pair(39.730116, 116.171359),
    "理学A" to Pair(39.728886, 116.171800),
    "理学B" to Pair(39.729267, 116.171739),
    "理学C" to Pair(39.729633, 116.171778),
    "文萃楼A" to Pair(39.732606, 116.174479),
    "文萃楼B" to Pair(39.732217, 116.174489),
    "文萃楼C" to Pair(39.731655, 116.174267),
    "文萃楼D" to Pair(39.731670, 116.173885),
    "文萃楼E" to Pair(39.731669, 116.173429),
    "文萃楼F" to Pair(39.732060, 116.173821),
    "文萃楼G" to Pair(39.732216, 116.173101),
    "文萃楼H" to Pair(39.732995, 116.173098),
    "文萃楼I" to Pair(39.733083, 116.173866),
    "文萃楼J" to Pair(39.733518, 116.173408),
    "文萃楼K" to Pair(39.733440, 116.173841),
    "文萃楼L" to Pair(39.733488, 116.174220),
    "文萃楼M" to Pair(39.733058, 116.174525),
    "良乡体育馆" to Pair(39.731844, 116.176544),
    "北校区篮球场" to Pair(39.736357, 116.170721),
    "南校区篮球场" to Pair(39.728026, 116.169304),
    "南校区排球场" to Pair(39.727381, 116.169604),
    "南校区足球场" to Pair(39.729583, 116.169174),
    "南校区网球场" to Pair(39.727967, 116.168370),
    "田径场主席台" to Pair(39.729490, 116.168474),
    "疏桐园A" to Pair(39.728834, 116.167727),
    "游泳馆" to Pair(39.731755, 116.177294),
    "化学实验中心" to Pair(39.727976, 116.170456),
    "工训楼" to Pair(39.726286, 116.173760),
    "西山阻燃楼" to Pair(40.037061, 116.232287),
    "物理实验中心" to Pair(39.729071, 116.170698),
)

internal val STATUS_MAP: Map<String, String> = mapOf(
    "01" to "排课占用",
    "10" to "排课占用(特定)",
    "02" to "考务占用",
    "03" to "其他占用",
    "04" to "借用占用",
    "05" to "调课占用",
    "11" to "特殊排课",
)

/** Mirrors `get_building_coord`. */
internal fun getBuildingCoord(jasmc: String?): Pair<Double, Double>? {
    if (jasmc.isNullOrEmpty()) return null
    return BUILDING_MAP.entries
        .sortedByDescending { it.key.length }
        .firstOrNull { (key, _) -> jasmc.contains(key) }
        ?.value
}

/**
 * Returns current kksj (semester code) based on the system clock.
 * Mirrors `services/jwb.py:get_current_kksj`.
 */
internal fun currentKksj(now: LocalDateTime = LocalDateTime.now()): String {
    val year = now.year
    return when (now.monthValue) {
        in 10..12 -> "$year-${year + 1}-1"
        in 1..3 -> "${year - 1}-$year-1"
        else -> "${year - 1}-$year-2"
    }
}
