package cn.bit101.bitlogin.util

/**
 * Mirrors Python's `urllib.parse.quote` and `urllib.parse.unquote` semantics
 * so that URL-encoding in this SDK produces byte-identical output to the
 * Python reference.
 *
 * Key differences from `java.net.URLEncoder`/`URLDecoder`:
 * - `quote` defaults to `safe="/"`, so `/` is NOT percent-encoded.
 * - `unquote` does NOT convert `+` to space (only `%XX` sequences are decoded).
 */
object PythonUrlEncoding {
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val ALWAYS_SAFE: Set<Char> = HashSet("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-".toList())

    fun quote(s: String, safe: String = "/"): String {
        val safeSet = ALWAYS_SAFE + safe.toSet()
        val result = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val ch = b.toInt() and 0xFF
            val c = ch.toChar()
            if (c in safeSet) {
                result.append(c)
            } else {
                result.append('%')
                result.append(HEX[ch ushr 4])
                result.append(HEX[ch and 0x0F])
            }
        }
        return result.toString()
    }

    fun unquote(s: String): String {
        if ('%' !in s) return s
        val src = s.encodeToByteArray()
        val out = ArrayList<Byte>()
        var i = 0
        while (i < src.size) {
            val b = src[i]
            if (b == '%'.code.toByte() && i + 2 < src.size) {
                val hi = hexValue(src[i + 1])
                val lo = hexValue(src[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out.add((hi shl 4 or lo).toByte())
                    i += 3
                    continue
                }
            }
            out.add(b)
            i++
        }
        return out.toByteArray().decodeToString()
    }

    fun urlJoin(base: String, reference: String): String {
        if (reference.isEmpty()) return base
        return try {
            when {
                reference.startsWith("?") -> base.substringBefore('#').substringBefore('?') + reference
                reference.startsWith("#") -> base.substringBefore('#') + reference
                else -> uriResolve(base, reference)
            }
        } catch (_: Exception) {
            reference
        }
    }

    private fun hexValue(b: Byte): Int = when (b) {
        in '0'.code.toByte()..'9'.code.toByte() -> b - '0'.code.toByte()
        in 'A'.code.toByte()..'F'.code.toByte() -> b - 'A'.code.toByte() + 10
        in 'a'.code.toByte()..'f'.code.toByte() -> b - 'a'.code.toByte() + 10
        else -> -1
    }
}
