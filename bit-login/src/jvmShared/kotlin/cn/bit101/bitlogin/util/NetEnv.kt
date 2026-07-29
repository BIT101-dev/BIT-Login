package cn.bit101.bitlogin.util

import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

actual object NetEnv {
    actual fun checkNetworkEnv(target: String, timeoutMs: Long): Boolean = try {
        val future = CompletableFuture.supplyAsync { InetAddress.getByName(target) }
        future.get(timeoutMs, TimeUnit.MILLISECONDS) != null
    } catch (e: Exception) {
        false
    }
}
