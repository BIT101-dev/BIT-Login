package cn.bit101.bitlogin.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BaseCredentials(
    val username: String = "",
    val password: String = "",
    @SerialName("challenge_id") val challengeId: String? = null,
)

@Serializable
data class JwbScoreRequest(
    val username: String = "",
    val password: String = "",
    val kksj: String? = null,
    val detail: Boolean = false,
    val detailed: Boolean? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
) {
    /** Mirrors Python `_score_detailed`: `detailed` takes precedence over `detail`. */
    fun resolvedDetailed(): Boolean = detailed ?: detail
}

@Serializable
data class JwbAllScoreRequest(
    val username: String = "",
    val password: String = "",
    val detailed: Boolean = false,
    @SerialName("challenge_id") val challengeId: String? = null,
)

@Serializable
data class JxzxehallCoursesRequest(
    val username: String = "",
    val password: String = "",
    val kksj: String? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
)
