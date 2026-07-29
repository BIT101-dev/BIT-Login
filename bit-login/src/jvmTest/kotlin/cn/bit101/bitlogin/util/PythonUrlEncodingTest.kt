package cn.bit101.bitlogin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PythonUrlEncodingTest {

    @Test
    fun `quote leaves slash unencoded by default`() {
        assertEquals("http%3A//jwms.bit.edu.cn/", PythonUrlEncoding.quote("http://jwms.bit.edu.cn/"))
    }

    @Test
    fun `quote encodes everything when safe is empty`() {
        assertEquals("http%3A%2F%2Fjwms.bit.edu.cn%2F", PythonUrlEncoding.quote("http://jwms.bit.edu.cn/", safe = ""))
    }

    @Test
    fun `unquote decodes percent sequences but preserves plus`() {
        assertEquals("a+b c", PythonUrlEncoding.unquote("a+b%20c"))
    }

    @Test
    fun `roundtrip preserves typical callback URL`() {
        val original = "https://jwms.bit.edu.cn/score?ticket=ST-123+456"
        val encoded = PythonUrlEncoding.quote(original, safe = "/:")
        val decoded = PythonUrlEncoding.unquote(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `urlJoin matches Python for query-only reference`() {
        assertEquals(
            "https://example.test/dir/file?q=1",
            PythonUrlEncoding.urlJoin("https://example.test/dir/file?old=1", "?q=1"),
        )
    }

    @Test
    fun `urlJoin matches Python for fragment and relative paths`() {
        assertEquals(
            "https://example.test/dir/file#new",
            PythonUrlEncoding.urlJoin("https://example.test/dir/file#old", "#new"),
        )
        assertEquals(
            "https://example.test/next",
            PythonUrlEncoding.urlJoin("https://example.test/dir/file", "../next"),
        )
        assertEquals(
            "https://other.test/path",
            PythonUrlEncoding.urlJoin("https://example.test/dir/file", "//other.test/path"),
        )
    }
}
