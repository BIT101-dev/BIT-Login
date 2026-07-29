package cn.bit101.bitlogin.sso

data class BrowserFingerprintProfile(
    val fonts: List<String> = DEFAULT_FONTS,
    val deviceMemory: Int = 16,
    val hardwareConcurrency: Int = 10,
    val timezone: String = "Asia/Shanghai",
    val cpuClass: String = "not available",
    val platform: String = "MacIntel",
    val language: String = "zh-CN",
    val screenResolution: List<Int> = DEFAULT_SCREEN_RESOLUTION,
    val platformAuthenticator: String = "support",
) {
    fun build(cookieValue: String, userAgent: String, groupId: String = ""): Map<String, String> {
        val values = mapOf(
            "fonts" to jsList(fonts),
            "deviceMemory" to deviceMemory.toString(),
            "hardwareConcurrency" to hardwareConcurrency.toString(),
            "timezone" to jsString(timezone),
            "cpuClass" to jsString(cpuClass),
            "platform" to jsString(platform),
            "language" to jsString(language),
            "screenResolution" to jsIntList(screenResolution),
        )
        val combined = values.values.joinToString("")
        return mapOf(
            "fonts" to sha256(values.getValue("fonts")),
            "deviceMemory" to sha256(values.getValue("deviceMemory")),
            "hardwareConcurrency" to sha256(values.getValue("hardwareConcurrency")),
            "localgroupId" to groupId,
            "timezone" to values.getValue("timezone"),
            "cpuClass" to sha256(values.getValue("cpuClass")),
            "platform" to values.getValue("platform"),
            "language" to values.getValue("language"),
            "screenResolution" to values.getValue("screenResolution"),
            "fingerprint" to sha256(combined),
            "cookieValue" to cookieValue,
            "userAgent" to userAgent,
            "platformAuthenticator" to platformAuthenticator,
        )
    }

    companion object {
        val DEFAULT_FONTS = listOf("Arial", "Helvetica Neue", "PingFang SC", "Times New Roman")
        val DEFAULT_SCREEN_RESOLUTION = listOf(956, 1470)

        private fun jsString(s: String): String = "\"" +
            s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun jsList(items: List<String>): String =
            "[" + items.joinToString(",") { jsString(it) } + "]"

        private fun jsIntList(items: List<Int>): String =
            "[" + items.joinToString(",") { it.toString() } + "]"

        private fun sha256(value: String): String =
            Crypto.sha256(value.encodeToByteArray())
    }
}
