package cn.bit101.bitlogin.util

/**
 * Detect whether the current machine is on the BIT campus network
 * (can resolve internal-only hostnames). The JDK DNS-backed implementation
 * lives in the shared JVM source set.
 */
expect object NetEnv {
    fun checkNetworkEnv(
        target: String = "jwms.bit.edu.cn",
        timeoutMs: Long = 2000L,
    ): Boolean
}
