package cn.bit101.bitlogin.server.perf

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class PerfReport(
    val label: String,
    val count: Int,
    val opsPerSec: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
) {
    override fun toString(): String =
        "%-56s ops=%6d  ops/s=%9.1f  p50=%8.2fms  p95=%8.2fms  p99=%8.2fms  max=%9.2fms".format(
            label, count, opsPerSec, p50Ms, p95Ms, p99Ms, maxMs,
        )
}

class LatencyRecorder {
    private val samples = ConcurrentLinkedQueue<Long>()

    fun record(durationNs: Long) {
        samples.add(durationNs)
    }

    fun report(label: String, elapsedNs: Long): PerfReport {
        val sorted = samples.sorted()
        val count = sorted.size
        fun pct(p: Double): Double =
            if (count == 0) 0.0 else sorted[minOf((count * p).toInt(), count - 1)] / 1e6
        val elapsedSec = elapsedNs / 1e9
        return PerfReport(
            label = label,
            count = count,
            opsPerSec = if (elapsedSec > 0) count / elapsedSec else 0.0,
            p50Ms = pct(0.50),
            p95Ms = pct(0.95),
            p99Ms = pct(0.99),
            maxMs = (sorted.lastOrNull() ?: 0L) / 1e6,
        )
    }
}

/**
 * Run [opsPerWorker] iterations of [block] on each of [concurrency] IO
 * workers, recording per-op latency. Returns an aggregate report whose
 * ops/s is computed over the whole run's wall time.
 */
suspend fun runLoad(
    label: String,
    concurrency: Int,
    opsPerWorker: Int,
    block: suspend (workerIndex: Int, opIndex: Int) -> Unit,
): PerfReport {
    val recorder = LatencyRecorder()
    val startedAt = System.nanoTime()
    coroutineScope {
        repeat(concurrency) { worker ->
            launch(Dispatchers.IO) {
                repeat(opsPerWorker) { op ->
                    val opStart = System.nanoTime()
                    block(worker, op)
                    recorder.record(System.nanoTime() - opStart)
                }
            }
        }
    }
    return recorder.report(label, System.nanoTime() - startedAt)
}
