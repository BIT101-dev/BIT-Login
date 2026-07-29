package cn.bit101.bitlogin.server.auth

import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Regression tests for legacy SQLite schema handling.
 *
 * Mirrors Python `_detect_legacy_schema` in `server/auth.py:355-441`. An
 * existing DB with the old (encrypted, pre-`code`/`payload`) schema must be
 * dropped and recreated, not silently left in an incompatible state.
 */
class ChallengeStoreMigrationTest {

    @TempDir lateinit var tempDir: java.nio.file.Path

    private fun legacyDb(): java.nio.file.Path {
        val db = tempDir.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            // Old shape: auth_challenges exists with subject, but auth_sms_codes
            // lacks the `code` column and auth_service_sessions lacks `payload`.
            conn.createStatement().execute(
                """
                CREATE TABLE auth_challenges (
                    challenge_id TEXT PRIMARY KEY,
                    token_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    requested_services TEXT NOT NULL,
                    ready_services TEXT NOT NULL,
                    masked_phone TEXT NOT NULL,
                    sms_purpose TEXT NOT NULL,
                    error TEXT NOT NULL,
                    subject TEXT NOT NULL DEFAULT '',
                    created_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                )
                """.trimIndent(),
            )
            conn.createStatement().execute(
                "CREATE TABLE auth_sms_codes (challenge_id TEXT PRIMARY KEY, legacy_secret TEXT NOT NULL)",
            )
            conn.createStatement().execute(
                "CREATE TABLE auth_service_sessions (challenge_id TEXT NOT NULL, service TEXT NOT NULL, legacy_payload TEXT NOT NULL, PRIMARY KEY (challenge_id, service))",
            )
            conn.createStatement().execute(
                "INSERT INTO auth_sms_codes(challenge_id, legacy_secret) VALUES('old','xx')",
            )
        }
        return db
    }

    @Test
    fun `legacy sms and session schema is dropped and recreated`() {
        val db = legacyDb()
        ChallengeStore(database = db.toString())
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            val smsCols = conn.createStatement().executeQuery("PRAGMA table_info(auth_sms_codes)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
            assertTrue("code" in smsCols, "auth_sms_codes.code missing after migration: $smsCols")
            val sessionCols = conn.createStatement().executeQuery("PRAGMA table_info(auth_service_sessions)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
            assertTrue("payload" in sessionCols, "auth_service_sessions.payload missing after migration: $sessionCols")
            // Old data must be gone.
            val count = conn.createStatement().executeQuery("SELECT COUNT(*) FROM auth_sms_codes").use { rs ->
                if (rs.next()) rs.getInt(1) else -1
            }
            assertTrue(count == 0, "legacy sms row should be dropped, got $count")
        }
    }

    @Test
    fun `store is usable after migrating a legacy db`() = runBlocking {
        val db = legacyDb()
        val store = ChallengeStore(database = db.toString())
        val handle = store.create(listOf("jwb"), "user")
        store.complete(handle.challengeId)
        assertTrue(store.snapshot(handle.challengeId, handle.accessToken)["status"] == "authenticated")
    }
}
