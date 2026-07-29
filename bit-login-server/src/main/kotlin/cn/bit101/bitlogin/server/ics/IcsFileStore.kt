package cn.bit101.bitlogin.server.ics

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages ICS files on disk and exposes a download URL.
 * Mirrors Python `ICS_FILES` dict + `clear_ics_files()` thread.
 */
class IcsFileStore(
    private val baseDir: Path = Paths.get("/tmp"),
    private val baseUrl: String,
) {

    private data class Entry(val url: String, val file: Path, val generated: Instant)

    private val log = LoggerFactory.getLogger("bit-login-server.IcsFileStore")
    private val files = ConcurrentHashMap<String, Entry>()

    init {
        runCatching {
            if (Files.isDirectory(baseDir)) {
                Files.list(baseDir).use { stream ->
                    stream.filter { it.toString().endsWith(".ics") }.forEach {
                        runCatching { Files.deleteIfExists(it) }
                    }
                }
            }
        }.onFailure { log.warn("Startup cleanup failed: ${it.message}") }
    }

    suspend fun store(content: String): Pair<String, String> {
        val uuid = UUID.randomUUID().toString()
        val file = baseDir.resolve("$uuid.ics")
        Files.createDirectories(baseDir)
        Files.write(file, content.toByteArray(Charsets.UTF_8))
        val url = "$baseUrl/tmp/$uuid.ics"
        files[uuid] = Entry(url, file, Instant.now())
        return url to file.toString()
    }

    fun get(filename: String): Path? {
        if (!filename.endsWith(".ics")) return null
        val uuid = filename.removeSuffix(".ics")
        return files[uuid]?.file?.takeIf { Files.exists(it) }
    }

    suspend fun cleanupLoop(intervalMs: Long = 30L * 1000L, ttlMs: Long = 30L * 60L * 1000L) {
        while (true) {
            delay(intervalMs)
            val now = Instant.now().toEpochMilli()
            val expired = files.entries.filter { now - it.value.generated.toEpochMilli() > ttlMs }
            for ((key, entry) in expired) {
                runCatching { if (Files.exists(entry.file)) Files.delete(entry.file) }
                    .onFailure { log.error("Failed to delete ${entry.file}: ${it.message}") }
                files.remove(key)
            }
        }
    }
}
