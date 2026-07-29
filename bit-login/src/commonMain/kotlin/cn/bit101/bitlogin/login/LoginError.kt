package cn.bit101.bitlogin.login

/** Equivalent to Python `bit_login.login_error`. */
class LoginError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
