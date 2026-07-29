package cn.bit101.bitlogin.server.perf

import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import cn.bit101.bitlogin.http.HttpClient
import cn.bit101.bitlogin.server.auth.ChallengeHandle
import cn.bit101.bitlogin.server.auth.ChallengeStore

@Tag("perf")
class ChallengeStorePerfTest {
    @TempDir lateinit var tempDir: Path

    private fun newStore(): ChallengeStore = ChallengeStore(
        database = tempDir.resolve("perf.db").toString(),
        pendingTtl = 300,
        readyTtl = 1800,
        pollIntervalMs = 250,
    )

    @Test
    fun `mixed workload under contention`() = runBlocking {
        val store = newStore()
        val readPool = (1..64).map {
            val handle = store.create(listOf("jwb"), "user-$it")
            store.complete(handle.challengeId)
            handle
        }
        val session = HttpClient()
        session.addCookie("cas", "cookie", "login.bit.edu.cn")
        val created = ConcurrentLinkedQueue<ChallengeHandle>()

        for (concurrency in listOf(1, 8, 32, 64)) {
            val opsPerWorker = when {
                concurrency <= 1 -> 200
                concurrency <= 8 -> 100
                concurrency <= 32 -> 50
                else -> 25
            }
            val report = runLoad("store mixed C=$concurrency", concurrency, opsPerWorker) { worker, op ->
                val read = readPool[(worker + op) % readPool.size]
                when (op % 10) {
                    in 0..6 -> store.snapshot(read.challengeId, read.accessToken)
                    in 7..8 -> {
                        val handle = store.create(listOf("jwb"), "perf-$worker-$op")
                        store.storeService(handle.challengeId, "jwb", session, buildJsonObject {})
                        store.complete(handle.challengeId)
                        created.add(handle)
                    }
                    else -> store.cleanup()
                }
            }
            println(report)
        }

        for (handle in created) {
            assertEquals("authenticated", store.snapshot(handle.challengeId, handle.accessToken)["status"])
        }
        assertTrue(created.isNotEmpty(), "write lifecycle ops should have run")
        session.close()
        Unit
    }

    @Test
    fun `polling storm resolves promptly`() = runBlocking {
        val store = newStore()
        val handles = (1..32).map { store.create(listOf("jwb"), "poller-$it") }

        val startedAt = System.nanoTime()
        coroutineScope {
            val pollers = handles.map { handle ->
                async { store.waitUntilActionable(handle.challengeId, handle.accessToken, 2_000) }
            }
            val completer = async {
                delay(200)
                handles.forEach { store.complete(it.challengeId) }
            }
            pollers.awaitAll()
            completer.await()
        }
        val wallMs = (System.nanoTime() - startedAt) / 1e6
        println("polling storm: 32 pollers resolved in ${wallMs}ms")
        assertTrue(wallMs < 10_000, "polling storm took ${wallMs}ms")

        for (handle in handles) {
            assertEquals("authenticated", store.snapshot(handle.challengeId, handle.accessToken)["status"])
        }
        Unit
    }
}
