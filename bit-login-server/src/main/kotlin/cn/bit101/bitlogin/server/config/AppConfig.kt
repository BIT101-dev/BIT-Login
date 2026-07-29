package cn.bit101.bitlogin.server.config

/** Server configuration parsed from environment variables. */
data class AppConfig(
    val host: String,
    val port: Int,
    val connectTimeoutMs: Long,
    val socketTimeoutMs: Long,
    val baseUrl: String,
    val allowedCorsOrigins: Set<String>,
    val authDbPath: String,
    val authChallengeTtl: Int,
    val authSessionTtl: Int,
) {
    companion object {
        fun fromEnv(map: Map<String, String> = System.getenv()): AppConfig = AppConfig(
            host = map["HOST"]?.ifBlank { "0.0.0.0" } ?: "0.0.0.0",
            port = (map["PORT"] ?: "16384").toIntOrNull() ?: 16384,
            connectTimeoutMs = ((map["HTTP_CONNECT_TIMEOUT"] ?: "5").toLongOrNull() ?: 5L) * 1000L,
            socketTimeoutMs = ((map["HTTP_READ_TIMEOUT"] ?: "25").toLongOrNull() ?: 25L) * 1000L,
            baseUrl = map["BASE_URL"]?.ifBlank { "https://login.bit101.flwfdd.xyz" } ?: "https://login.bit101.flwfdd.xyz",
            allowedCorsOrigins = setOf(
                "https://bit101.cn",
                "http://bit101.cn",
                "http://127.0.0.1:3000",
                "http://localhost:3000",
                "https://deploy-preview-57--bit101-demo.netlify.app",
                "http://deploy-preview-57--bit101-demo.netlify.app",
            ),
            authDbPath = map["AUTH_DB_PATH"]?.ifBlank { "/tmp/bit-login/auth.db" } ?: "/tmp/bit-login/auth.db",
            authChallengeTtl = (map["AUTH_CHALLENGE_TTL"] ?: "300").toIntOrNull() ?: 300,
            authSessionTtl = (map["AUTH_SESSION_TTL"] ?: "1800").toIntOrNull() ?: 1800,
        )
    }
}
