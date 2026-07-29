package cn.bit101.bitlogin.server.auth

class ChallengeError(message: String) : Exception(message)

data class ChallengeHandle(val challengeId: String, val accessToken: String)
