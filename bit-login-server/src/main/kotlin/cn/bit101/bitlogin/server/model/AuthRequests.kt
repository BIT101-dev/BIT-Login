package cn.bit101.bitlogin.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthStartRequest(
    val username: String,
    val password: String,
    val services: List<String> = listOf("jwb"),
    @SerialName("wait_seconds") val waitSeconds: Double = 1.0,
)

@Serializable
data class SmsCodeRequest(val code: String)

@Serializable
data class RegistrationTokenRequest(val audience: String)
