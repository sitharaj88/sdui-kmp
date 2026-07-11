package dev.sdui.kmp.benchmarks

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sanity-only: the benchmark harness runs to completion on a single developer machine. We do
 * NOT assert absolute numbers — CI hardware varies, and without a proper JMH harness the
 * numbers have too much variance to lint against a budget. The value in this test is to
 * catch breakages: if a protocol change makes `runAll` throw, CI flags it.
 */
class BenchSanityTest {
    @Test
    fun every_benchmark_runs_and_reports_positive_ops_per_second() {
        val results = runAll()
        assertTrue(results.isNotEmpty(), "no benchmarks ran")
        results.forEach { r ->
            assertTrue(r.opsPerSec > 0, "benchmark '${r.name}' reported zero throughput")
        }
    }
}
