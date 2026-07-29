package cn.bit101.bitlogin.server.auth

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.sso.SmsCodeContext

class ChallengeStore(
    database: String,
    private val pendingTtl: Int = 300,
    private val readyTtl: Int = 1800,
    private val pollIntervalMs: Long = 250,
) {
    private val dbPath: String = Paths.get(database).toAbsolutePath().toString()
    private val random = SecureRandom()
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val json = Json { encodeDefaults = true }

    // Single persistent connection reused for all operations, serialized by
    // dbLock (SQLite connections are not thread-safe). PRAGMAs are set once
    // at open time; WAL mode persists in the DB file, so other processes
    // sharing this AUTH_DB keep working.
    private val dbLock = java.util.concurrent.locks.ReentrantLock()
    private val writerConn: Connection

    init {
        val parent = Paths.get(dbPath).parent
        val dirAttrs = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        )
        try {
            Files.createDirectories(parent, dirAttrs)
        } catch (_: UnsupportedOperationException) {
            Files.createDirectories(parent)
        }
        val path = Paths.get(dbPath)
        if (!Files.exists(path)) {
            val fileAttrs = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            )
            try {
                Files.createFile(path, fileAttrs)
            } catch (_: UnsupportedOperationException) {
                Files.createFile(path)
            }
        }
        writerConn = openConnection()
        initialize()
        hardenFiles()
    }

    companion object {
        fun fromEnv(map: Map<String, String> = System.getenv()): ChallengeStore = ChallengeStore(
            database = map["AUTH_DB_PATH"]?.ifBlank { "/tmp/bit-login/auth.db" } ?: "/tmp/bit-login/auth.db",
            pendingTtl = (map["AUTH_CHALLENGE_TTL"] ?: "300").toIntOrNull() ?: 300,
            readyTtl = (map["AUTH_SESSION_TTL"] ?: "1800").toIntOrNull() ?: 1800,
        )

        private val SENSITIVE_ERROR = Regex(
            """(?i)(\b(?:access[_-]?token|response[_-]?token|ticket|cas|sms[_-]?code|password|passwd)\b\s*[=:]\s*)([^\s&,;\])}]+)"""
        )
        private val BEARER_TOKEN = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/=-]+""")
    }

    suspend fun create(services: List<String>, subject: String = ""): ChallengeHandle = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val challengeId = randomToken(18)
        val accessToken = randomToken(32)
        val now = System.currentTimeMillis() / 1000.0
        transaction(true) { conn ->
            conn.prepareStatement(
                "INSERT INTO auth_challenges (challenge_id, token_hash, status, requested_services, ready_services, masked_phone, sms_purpose, error, subject, created_at, expires_at) VALUES (?, ?, 'running', ?, '[]', '', '', '', ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, challengeId)
                stmt.setString(2, tokenHash(accessToken))
                stmt.setString(3, encodeJson(services))
                stmt.setString(4, subject)
                stmt.setDouble(5, now)
                stmt.setDouble(6, now + pendingTtl)
                stmt.executeUpdate()
            }
        }
        ChallengeHandle(challengeId, accessToken)
    }

    suspend fun authenticate(challengeId: String, accessToken: String): Map<String, Any?> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val state = state(challengeId)
        val expected = tokenHash(accessToken)
        if (!MessageDigest.isEqual(
                (state["token_hash"] as? String ?: "").toByteArray(),
                expected.toByteArray()
            )
        ) throw ChallengeError("invalid challenge access token")
        state
    }

    suspend fun snapshot(challengeId: String, accessToken: String, includeAccessToken: Boolean = false): Map<String, Any?> {
        val state = authenticate(challengeId, accessToken)
        val now = System.currentTimeMillis() / 1000.0
        val result = mutableMapOf<String, Any?>(
            "challenge_id" to challengeId,
            "status" to state["status"],
            "requested_services" to state["requested_services"],
            "ready_services" to state["ready_services"],
            "expires_in" to maxOf(0, ((state["expires_at"] as Number).toDouble() - now).toInt()),
        )
        if (state["status"] == "waiting_sms") {
            result["masked_phone"] = (state["masked_phone"] as? String ?: "").ifBlank { "绑定手机" }
            result["sms_purpose"] = (state["sms_purpose"] as? String ?: "").ifBlank { "password_second_factor" }
        }
        if (state["status"] == "failed") {
            result["error"] = (state["error"] as? String ?: "").ifBlank { "authentication failed" }
        }
        if (includeAccessToken) result["access_token"] = accessToken
        return result
    }

    suspend fun waitForSms(challengeId: String, context: SmsCodeContext): String {
        val now = System.currentTimeMillis() / 1000.0
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            updateChallenge(challengeId, mapOf(
                "status" to "waiting_sms",
                "masked_phone" to (context.maskedPhone.ifBlank { "绑定手机" }),
                "sms_purpose" to context.purpose,
                "expires_at" to (now + pendingTtl),
            ))
        }
        val deadline = System.nanoTime() / 1e9 + pendingTtl
        while (System.nanoTime() / 1e9 < deadline) {
            val code = withContext(kotlinx.coroutines.Dispatchers.IO) { takeSmsCode(challengeId) }
            if (code != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) { updateChallenge(challengeId, mapOf("status" to "processing")) }
                return code
            }
            val st = withContext(kotlinx.coroutines.Dispatchers.IO) { state(challengeId) }
            val status = st["status"] as? String ?: ""
            if (status in setOf("cancelled", "expired", "failed")) throw ChallengeError("challenge is $status")
            delay(pollIntervalMs)
        }
        fail(challengeId, ChallengeError("SMS challenge expired"), "expired")
        throw ChallengeError("SMS challenge expired")
    }

    suspend fun submitSms(challengeId: String, accessToken: String, code: String) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmed = code.trim()
        if (!Regex("[0-9]{4,8}").matches(trimmed)) throw ChallengeError("SMS code must contain 4 to 8 digits")
        authenticate(challengeId, accessToken)
        val now = System.currentTimeMillis() / 1000.0
        transaction(true) { conn ->
            conn.prepareStatement("SELECT status FROM auth_challenges WHERE challenge_id = ?").use { stmt ->
                stmt.setString(1, challengeId)
                val rs = stmt.executeQuery()
                if (!rs.next()) throw ChallengeError("unknown or expired authentication challenge")
                if (rs.getString("status") != "waiting_sms") throw ChallengeError("challenge is not waiting for an SMS code")
            }
            try {
                conn.prepareStatement("INSERT INTO auth_sms_codes (challenge_id, code, expires_at) VALUES (?, ?, ?)").use { stmt ->
                    stmt.setString(1, challengeId)
                    stmt.setString(2, trimmed)
                    stmt.setDouble(3, now + pendingTtl)
                    stmt.executeUpdate()
                }
            } catch (e: java.sql.SQLIntegrityConstraintViolationException) {
                throw ChallengeError("an SMS code has already been submitted")
            }
            conn.prepareStatement("UPDATE auth_challenges SET status = 'processing', expires_at = ? WHERE challenge_id = ?").use { stmt ->
                stmt.setDouble(1, now + pendingTtl)
                stmt.setString(2, challengeId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun storeService(challengeId: String, service: String, session: HttpClient, result: JsonObject? = null) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val payload = buildJsonObject {
            put("session", SessionSerializer.serialize(session))
            put("result", result ?: buildJsonObject {})
        }
        val payloadJson = json.encodeToString(JsonObject.serializer(), payload)
        val now = System.currentTimeMillis() / 1000.0
        transaction(true) { conn ->
            var readyServices: MutableList<String> = mutableListOf()
            conn.prepareStatement("SELECT ready_services FROM auth_challenges WHERE challenge_id = ?").use { stmt ->
                stmt.setString(1, challengeId)
                val rs = stmt.executeQuery()
                if (!rs.next()) throw ChallengeError("unknown or expired authentication challenge")
                readyServices = decodeList(rs.getString("ready_services")).toMutableList()
            }
            if (service !in readyServices) readyServices.add(service)
            conn.prepareStatement(
                "INSERT INTO auth_service_sessions (challenge_id, service, payload, expires_at) VALUES (?, ?, ?, ?) ON CONFLICT(challenge_id, service) DO UPDATE SET payload = excluded.payload, expires_at = excluded.expires_at"
            ).use { stmt ->
                stmt.setString(1, challengeId)
                stmt.setString(2, service)
                stmt.setString(3, payloadJson)
                stmt.setDouble(4, now + readyTtl)
                stmt.executeUpdate()
            }
            conn.prepareStatement("UPDATE auth_challenges SET ready_services = ?, expires_at = ? WHERE challenge_id = ?").use { stmt ->
                stmt.setString(1, encodeJson(readyServices.sorted()))
                stmt.setDouble(2, now + pendingTtl)
                stmt.setString(3, challengeId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun complete(challengeId: String) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val expiresAt = System.currentTimeMillis() / 1000.0 + readyTtl
        transaction(true) { conn ->
            val updated = conn.prepareStatement(
                "UPDATE auth_challenges SET status = 'authenticated', masked_phone = '', sms_purpose = '', expires_at = ? WHERE challenge_id = ?"
            ).use { stmt ->
                stmt.setDouble(1, expiresAt)
                stmt.setString(2, challengeId)
                stmt.executeUpdate()
            }
            if (updated != 1) throw ChallengeError("unknown or expired authentication challenge")
            conn.prepareStatement("UPDATE auth_service_sessions SET expires_at = ? WHERE challenge_id = ?").use { stmt ->
                stmt.setDouble(1, expiresAt)
                stmt.setString(2, challengeId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun fail(challengeId: String, error: Throwable, status: String = "failed") {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                updateChallenge(challengeId, mapOf("status" to status, "error" to safeError(error)))
            } catch (_: ChallengeError) {}
        }
    }

    suspend fun getSession(challengeId: String, accessToken: String, service: String): HttpClient {
        val state = authenticate(challengeId, accessToken)
        if (state["status"] != "authenticated") throw ChallengeError("challenge is ${state["status"]}")
        val payload = servicePayload(challengeId, service)
        return SessionSerializer.restore(payload["session"] as? JsonObject ?: buildJsonObject {})
    }

    suspend fun getResult(challengeId: String, accessToken: String, service: String): JsonObject {
        val state = authenticate(challengeId, accessToken)
        if (state["status"] != "authenticated") throw ChallengeError("challenge is ${state["status"]}")
        return servicePayload(challengeId, service)["result"] as? JsonObject ?: buildJsonObject {}
    }

    suspend fun delete(challengeId: String, accessToken: String) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        authenticate(challengeId, accessToken)
        transaction(true) { conn ->
            conn.prepareStatement("DELETE FROM auth_challenges WHERE challenge_id = ?").use { stmt ->
                stmt.setString(1, challengeId)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun cleanup(): Int = withContext(kotlinx.coroutines.Dispatchers.IO) { cleanupExpired() }

    private fun cleanupExpired(): Int {
        val now = System.currentTimeMillis() / 1000.0
        return transaction(true) { conn ->
            val count = conn.prepareStatement("DELETE FROM auth_challenges WHERE expires_at <= ?").use { stmt ->
                stmt.setDouble(1, now)
                stmt.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM auth_sms_codes WHERE expires_at <= ?").use { stmt ->
                stmt.setDouble(1, now)
                stmt.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM auth_service_sessions WHERE expires_at <= ?").use { stmt ->
                stmt.setDouble(1, now)
                stmt.executeUpdate()
            }
            maxOf(0, count)
        }
    }

    suspend fun waitUntilActionable(challengeId: String, accessToken: String, timeoutMs: Long) {
        val deadline = System.nanoTime() / 1e9 + timeoutMs / 1000.0
        while (System.nanoTime() / 1e9 < deadline) {
            val status = authenticate(challengeId, accessToken)["status"] as? String ?: ""
            if (status in setOf("waiting_sms", "authenticated", "failed", "expired")) return
            delay(minOf(pollIntervalMs, maxOf(0.0, (deadline - System.nanoTime() / 1e9) * 1000).toLong()))
        }
    }

    private fun initialize() {
        connection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
                detectAndMigrateSchema(conn)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS auth_challenges (
                        challenge_id TEXT PRIMARY KEY,
                        token_hash TEXT NOT NULL,
                        status TEXT NOT NULL,
                        requested_services TEXT NOT NULL,
                        ready_services TEXT NOT NULL,
                        masked_phone TEXT NOT NULL,
                        sms_purpose TEXT NOT NULL,
                        error TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        created_at REAL NOT NULL,
                        expires_at REAL NOT NULL
                    )
                """)
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_challenges_expires ON auth_challenges(expires_at)")
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS auth_sms_codes (
                        challenge_id TEXT PRIMARY KEY REFERENCES auth_challenges(challenge_id) ON DELETE CASCADE,
                        code TEXT NOT NULL,
                        expires_at REAL NOT NULL
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS auth_service_sessions (
                        challenge_id TEXT NOT NULL REFERENCES auth_challenges(challenge_id) ON DELETE CASCADE,
                        service TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        expires_at REAL NOT NULL,
                        PRIMARY KEY (challenge_id, service)
                    )
                """)
            }
        }
    }

    private fun detectAndMigrateSchema(conn: Connection) {
        fun columns(table: String): Set<String> = conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
        }
        val smsColumns = columns("auth_sms_codes")
        val sessionColumns = columns("auth_service_sessions")
        val challengeColumns = columns("auth_challenges")
        val legacySchema = (smsColumns.isNotEmpty() && "code" !in smsColumns) ||
            (sessionColumns.isNotEmpty() && "payload" !in sessionColumns)
        if (legacySchema) {
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS auth_sms_codes")
                stmt.execute("DROP TABLE IF EXISTS auth_service_sessions")
                stmt.execute("DROP TABLE IF EXISTS auth_challenges")
            }
            return
        }
        if (challengeColumns.isNotEmpty() && (smsColumns.isEmpty() || sessionColumns.isEmpty())) {
            conn.createStatement().use { it.execute("DELETE FROM auth_challenges") }
        }
        if (challengeColumns.isNotEmpty() && "subject" !in challengeColumns) {
            try {
                conn.createStatement().use { it.execute("ALTER TABLE auth_challenges ADD COLUMN subject TEXT NOT NULL DEFAULT ''") }
            } catch (e: java.sql.SQLException) {
                if (!e.message.orEmpty().contains("duplicate column name", ignoreCase = true)) throw e
            }
        }
    }

    /** Re-chmod db, -wal, -shm to 0600 after every connection. */
    private fun hardenFiles() {
        val perms = try {
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        } catch (_: Throwable) {
            return
        }
        val basePath = Paths.get(dbPath)
        listOf(basePath, Paths.get("$dbPath-wal"), Paths.get("$dbPath-shm")).forEach { p ->
            if (Files.exists(p)) {
                runCatching { Files.setPosixFilePermissions(p, perms) }
            }
        }
    }

    private fun openConnection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        conn.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
        return conn
    }

    private fun connection(block: (Connection) -> Unit) {
        dbLock.withLock { block(writerConn) }
    }

    private fun <T> transaction(immediate: Boolean, block: (Connection) -> T): T {
        dbLock.withLock {
            writerConn.createStatement().use { it.execute(if (immediate) "BEGIN IMMEDIATE" else "BEGIN") }
            try {
                val result = block(writerConn)
                writerConn.createStatement().execute("COMMIT")
                return result
            } catch (e: Throwable) {
                writerConn.createStatement().execute("ROLLBACK")
                throw e
            }
        }
    }

    private fun state(challengeId: String): Map<String, Any?> {
        var state: Map<String, Any?>? = null
        connection { conn ->
            conn.prepareStatement("SELECT * FROM auth_challenges WHERE challenge_id = ?").use { stmt ->
                stmt.setString(1, challengeId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    state = mapOf(
                        "token_hash" to rs.getString("token_hash"),
                        "status" to rs.getString("status"),
                        "requested_services" to decodeList(rs.getString("requested_services")),
                        "ready_services" to decodeList(rs.getString("ready_services")),
                        "masked_phone" to rs.getString("masked_phone"),
                        "sms_purpose" to rs.getString("sms_purpose"),
                        "error" to rs.getString("error"),
                        "subject" to rs.getString("subject"),
                        "expires_at" to rs.getDouble("expires_at"),
                    )
                }
            }
        }
        if (state == null) throw ChallengeError("unknown or expired authentication challenge")
        val now = System.currentTimeMillis() / 1000.0
        if ((state!!["expires_at"] as Number).toDouble() <= now) {
            throw ChallengeError("unknown or expired authentication challenge")
        }
        return state!!
    }

    private fun updateChallenge(challengeId: String, fields: Map<String, Any?>) {
        val allowed = setOf("status", "masked_phone", "sms_purpose", "error", "expires_at")
        require(fields.isNotEmpty() && fields.keys.all { it in allowed }) { "invalid challenge update" }
        val assignments = fields.keys.joinToString(", ") { "$it = ?" }
        transaction(true) { conn ->
            val updated = conn.prepareStatement("UPDATE auth_challenges SET $assignments WHERE challenge_id = ?").use { stmt ->
                fields.values.forEachIndexed { i, v ->
                    when (v) {
                        is Number -> stmt.setDouble(i + 1, v.toDouble())
                        is String -> stmt.setString(i + 1, v)
                        else -> stmt.setString(i + 1, v?.toString() ?: "")
                    }
                }
                stmt.setString(fields.size + 1, challengeId)
                stmt.executeUpdate()
            }
            if (updated != 1) throw ChallengeError("unknown or expired authentication challenge")
        }
    }

    private fun takeSmsCode(challengeId: String): String? {
        var code: String? = null
        val now = System.currentTimeMillis() / 1000.0
        transaction(true) { conn ->
            conn.prepareStatement("SELECT code FROM auth_sms_codes WHERE challenge_id = ? AND expires_at > ?").use { stmt ->
                stmt.setString(1, challengeId)
                stmt.setDouble(2, now)
                val rs = stmt.executeQuery()
                if (rs.next()) code = rs.getString("code")
            }
            if (code != null) {
                conn.prepareStatement("DELETE FROM auth_sms_codes WHERE challenge_id = ?").use { stmt ->
                    stmt.setString(1, challengeId)
                    stmt.executeUpdate()
                }
            }
        }
        return code
    }

    private fun servicePayload(challengeId: String, service: String): JsonObject {
        var payload: JsonObject? = null
        val now = System.currentTimeMillis() / 1000.0
        connection { conn ->
            conn.prepareStatement("SELECT payload FROM auth_service_sessions WHERE challenge_id = ? AND service = ? AND expires_at > ?").use { stmt ->
                stmt.setString(1, challengeId)
                stmt.setString(2, service)
                stmt.setDouble(3, now)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    payload = Json.parseToJsonElement(rs.getString("payload")) as? JsonObject
                }
            }
        }
        return payload ?: throw ChallengeError("service is not ready: $service")
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return base64Encoder.encodeToString(buf)
    }

    private fun tokenHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encodeJson(value: List<String>): String =
        Json.encodeToString(JsonArray.serializer(), JsonArray(value.map { JsonPrimitive(it) }))

    private fun decodeList(value: String): List<String> = try {
        (Json.parseToJsonElement(value) as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun safeError(error: Throwable): String {
        var value = error.message?.ifBlank { error::class.simpleName } ?: error::class.simpleName.orEmpty()
        value = SENSITIVE_ERROR.replace(value, "$1[redacted]")
        value = BEARER_TOKEN.replace(value, "$1[redacted]")
        return value.take(500)
    }
}
