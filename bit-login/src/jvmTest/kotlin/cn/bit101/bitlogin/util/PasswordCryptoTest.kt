package cn.bit101.bitlogin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PasswordCryptoTest {

    /** Golden vectors from Python `bit_login.utils.encrypt_password`. */
    private val vectors: List<Triple<String, String, String>> = listOf(
        Triple("", "", ""),
        Triple("password", "xor:EggHXgQcHUk=", "password"),
        Triple("中文密码", "xor:htHZy+X0ioLHktTu", "中文密码"),
        Triple("!@#\$%^&*()", "xor:QylXCVYtSQdpXA==", "!@#\$%^&*()"),
        Triple("a", "xor:Aw==", "a"),
        Triple("P@ssw0rd123", "xor:MikHXgRDHUlwR0c=", "P@ssw0rd123"),
    )

    @Test
    fun `encryptPassword matches Python golden vectors`() {
        vectors.forEach { (input, expected, _) ->
            assertEquals(expected, PasswordCrypto.encryptPassword(input), "input=$input")
        }
    }

    @Test
    fun `decryptPassword round-trips through encryptPassword`() {
        vectors.forEach { (input, _, _) ->
            assertEquals(input, PasswordCrypto.decryptPassword(PasswordCrypto.encryptPassword(input)))
        }
    }

    @Test
    fun `decryptPassword decrypts Python golden ciphertexts`() {
        vectors.forEach { (expected, encoded, _) ->
            assertEquals(expected, PasswordCrypto.decryptPassword(encoded), "encoded=$encoded")
        }
    }

    @Test
    fun `decryptPassword returns input when not xor-prefixed`() {
        assertEquals("hello", PasswordCrypto.decryptPassword("hello"))
        assertEquals("", PasswordCrypto.decryptPassword(""))
    }

    @Test
    fun `decryptPassword returns input on invalid base64`() {
        assertEquals("xor:not-base64!!", PasswordCrypto.decryptPassword("xor:not-base64!!"))
    }

    @Test
    fun `encryptPassword of empty is empty`() {
        assertEquals("", PasswordCrypto.encryptPassword(""))
        assertFalse("xor:".let { PasswordCrypto.encryptPassword("").startsWith(it) })
    }
}
