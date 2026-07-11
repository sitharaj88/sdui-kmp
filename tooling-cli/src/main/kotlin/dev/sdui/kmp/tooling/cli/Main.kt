package dev.sdui.kmp.tooling.cli

import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point for the schema linter / snapshot tool.
 *
 * Subcommands:
 *   - `capture <out.json>` — walk the current `:protocol` types and write a snapshot file.
 *   - `lint <baseline.json>` — walk the current types, compare to baseline, print violations
 *     and exit non-zero if any were found.
 */
public fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: run {
        printUsage()
        exitProcess(2)
    }
    when (command) {
        "capture" -> runCapture(args.drop(1))
        "lint" -> runLint(args.drop(1))
        "-h", "--help", "help" -> printUsage()
        else -> {
            System.err.println("unknown command: $command")
            printUsage()
            exitProcess(2)
        }
    }
}

private fun runCapture(args: List<String>) {
    val outPath = args.firstOrNull() ?: run {
        System.err.println("capture requires an output path")
        exitProcess(2)
    }
    val snapshot = captureProtocolSnapshot()
    val json = ProtocolSnapshot.Json.encodeToString(ProtocolSnapshot.serializer(), snapshot)
    File(outPath).writeText(json + "\n")
    println("wrote $outPath")
}

private fun runLint(args: List<String>) {
    val baselinePath = args.firstOrNull() ?: run {
        System.err.println("lint requires a baseline path")
        exitProcess(2)
    }
    val baselineFile = File(baselinePath)
    if (!baselineFile.exists()) {
        System.err.println("baseline not found: $baselinePath")
        System.err.println("run `capture` first to generate one")
        exitProcess(2)
    }
    val baseline = ProtocolSnapshot.Json.decodeFromString(
        ProtocolSnapshot.serializer(),
        baselineFile.readText(),
    )
    val current = captureProtocolSnapshot()
    val violations = lintProtocol(baseline, current)
    if (violations.isEmpty()) {
        println("protocol snapshot: no breaking changes vs baseline")
        return
    }
    System.err.println("protocol snapshot: ${violations.size} breaking change(s) vs baseline:")
    for (v in violations) {
        System.err.println("  [${v.kind}] ${v.path} — ${v.message}")
    }
    exitProcess(1)
}

private fun printUsage() {
    println(
        """
        sdui-tooling-cli — schema snapshot + linter
        Usage:
          capture <out.json>    Walk :protocol and write a snapshot
          lint    <baseline>    Compare baseline to current :protocol; fail on breaking change
        """.trimIndent(),
    )
}
