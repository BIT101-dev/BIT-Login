package cn.bit101.bitlogin.util

/**
 * XOR + base64 password obfuscation, byte-for-byte port of
 * Python `bit_login/utils.py:{encrypt_password, decrypt_password}`.
 */
object PasswordCrypto {
    private val XOR_KEY = "bit-sso-AutoLogin-key".encodeToByteArray()

    fun encryptPassword(pwd: String): String {
        if (pwd.isEmpty()) return ""
        val input = pwd.encodeToByteArray()
        val output = ByteArray(input.size)
        for (i in input.indices) {
            output[i] = (input[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return "xor:" + base64Encode(output)
    }

    fun decryptPassword(s: String): String {
        if (s.isEmpty() || !s.startsWith("xor:")) return s
        return try {
            val b64 = base64Decode(s.substring(4))
            val output = ByteArray(b64.size)
            for (i in b64.indices) {
                output[i] = (b64[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
            }
            output.decodeToString()
        } catch (e: Exception) {
            s
        }
    }
}
