package cn.bit101.bitlogin.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetEnvTest {

    @Test
    fun `checkNetworkEnv returns false for unresolvable host`() {
        // 53-char label exceeds DNS limit (63) guaranteed to be NXDOMAIN.
        val longHost = "a".repeat(64) + ".example.com"
        assertFalse(NetEnv.checkNetworkEnv(target = longHost, timeoutMs = 1500L))
    }

    @Test
    fun `checkNetworkEnv returns true for well-known resolvable host`() {
        // localhost always resolves.
        assertTrue(NetEnv.checkNetworkEnv(target = "localhost", timeoutMs = 1000L))
    }
}
