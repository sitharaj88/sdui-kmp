package dev.sdui.kmp.benchmarks

import kotlin.time.TimeSource

/**
 * Single microbench data point.
 *
 * [ns] is the per-operation wall-clock time in nanoseconds. [opsPerSec] is derived for
 * readability. For sub-millisecond operations, JIT effects dominate raw numbers — treat
 * these as relative comparisons, not absolute perf guarantees.
 */
public data class BenchResult(
    public val name: String,
    public val iterations: Int,
    public val ns: Long,
    public val opsPerSec: Double,
) {
    override fun toString(): String {
        val nsStr = when {
            ns < 1_000 -> "${ns}ns"
            ns < 1_000_000 -> "${ns / 1_000}µs"
            else -> "${ns / 1_000_000}ms"
        }
        val opsStr = when {
            opsPerSec >= 1_000_000 -> "${(opsPerSec / 1_000_000).toInt()}M ops/s"
            opsPerSec >= 1_000 -> "${(opsPerSec / 1_000).toInt()}k ops/s"
            else -> "${opsPerSec.toInt()} ops/s"
        }
        return "%-60s  %10s/op  %10s".format(name, nsStr, opsStr)
    }
}

/**
 * Measures [block] — warms up for [iterations]/10 runs to let the JIT settle, then times
 * [iterations] runs and reports per-op nanoseconds.
 *
 * Not a JMH replacement: we don't prevent dead-code elimination or measure with multiple
 * forks. Enough to surface regressions between protocol versions — more rigor can come with
 * a proper `kotlinx-benchmark` harness later.
 */
public fun bench(name: String, iterations: Int = 10_000, block: () -> Any?): BenchResult {
    // Warmup.
    repeat((iterations / 10).coerceAtLeast(1)) { block() }
    val mark = TimeSource.Monotonic.markNow()
    repeat(iterations) { block() }
    val elapsed = mark.elapsedNow().inWholeNanoseconds
    val perOp = elapsed / iterations
    val opsPerSec = if (elapsed > 0) iterations.toDouble() * 1_000_000_000 / elapsed else 0.0
    return BenchResult(name, iterations, perOp, opsPerSec)
}
