package cn.bit101.bitlogin.util

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

actual object WebVpnUrl {
    private const val DEFAULT_KEY = "wrdvpnisthebest!"
    private const val DEFAULT_IV = "wrdvpnisthebest!"
    private val HEX = "0123456789abcdef".toCharArray()

    actual fun encodeVpnHost(
        host: String,
        vpnKeyStr: String,
        vpnIvStr: String,
    ): String {
        val key = vpnKeyStr.toByteArray(Charsets.UTF_8)
        val iv = vpnIvStr.toByteArray(Charsets.UTF_8)

        // Hosts are ASCII; we mirror Python len(host) by treating char count.
        val textLen = host.length
        val padLen = (16 - textLen % 16) % 16
        val padded = (host + "0".repeat(padLen)).toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))

        val ciphertext = ByteArray(padded.size)
        var feedback = iv.copyOf()
        var i = 0
        while (i < padded.size) {
            val keystream = cipher.doFinal(feedback)
            val block = ByteArray(16)
            for (j in 0 until 16) {
                if (i + j < padded.size) {
                    val b = (padded[i + j].toInt() xor keystream[j].toInt()).toByte()
                    block[j] = b
                    ciphertext[i + j] = b
                }
            }
            feedback = block
            i += 16
        }

        return iv.toHex() + ciphertext.toHex().substring(0, textLen * 2)
    }

    /**
     * Rewrite an internal BIT URL into its WebVPN form.
     * Mirrors Python `convert_to_webvpn_url`.
     */
    actual fun convertToWebvpnUrl(originalUrl: String): String {
        val parsed = runCatching { java.net.URI(originalUrl) }.getOrNull() ?: return originalUrl
        val targetProto = parsed.scheme ?: return originalUrl
        val targetHost = parsed.host ?: return originalUrl
        if (targetHost.isEmpty()) return originalUrl

        val encodedHost = encodeVpnHost(targetHost)
        val newPath = "/$targetProto/$encodedHost${parsed.rawPath ?: ""}"

        val sb = StringBuilder()
        sb.append("https://webvpn.bit.edu.cn").append(newPath)
        parsed.rawQuery?.takeIf { it.isNotEmpty() }?.let { sb.append('?').append(it) }
        parsed.rawFragment?.takeIf { it.isNotEmpty() }?.let { sb.append('#').append(it) }
        return sb.toString()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }
}
