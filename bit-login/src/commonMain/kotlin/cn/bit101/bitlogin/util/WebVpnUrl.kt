package cn.bit101.bitlogin.util

/**
 * WebVPN host encoding & URL rewriting. The implementation uses AES via JCA and
 * lives in the shared JVM source set; only the signatures live in common.
 *
 * Algorithm ported verbatim from Python `bit_login/utils.py:encode_vpn_host`.
 */
expect object WebVpnUrl {
    fun encodeVpnHost(
        host: String,
        vpnKeyStr: String = "wrdvpnisthebest!",
        vpnIvStr: String = "wrdvpnisthebest!",
    ): String

    fun convertToWebvpnUrl(originalUrl: String): String
}
