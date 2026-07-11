package dev.sdui.kmp.tooling.preview

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Emits the text contents of [file] once on subscription and again whenever the file is
 * modified on disk.
 *
 * Implementation: JVM's [java.nio.file.WatchService] on the file's parent directory, filtered
 * to the target basename. The service only reports `MODIFY` events, which is what editors
 * emit on save. The flow survives transient read failures (e.g. an editor atomically
 * rewriting the file) — the next modify event re-reads.
 */
internal fun watchFile(file: Path): Flow<String> = channelFlow {
    trySend(file.toFile().readText())
    val dir = file.parent ?: error("Preview file must live in a directory")
    val watcher = FileSystems.getDefault().newWatchService()
    dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
    try {
        while (currentCoroutineContext().isActive) {
            val key: WatchKey = withContext(Dispatchers.IO) { watcher.take() }
            for (event in key.pollEvents()) {
                val changed = event.context()?.toString() ?: continue
                if (changed == file.fileName.toString()) {
                    runCatching { file.toFile().readText() }
                        .onSuccess { trySend(it) }
                }
            }
            if (!key.reset()) break
        }
    } finally {
        watcher.close()
    }
}.flowOn(Dispatchers.IO)
