package dev.sdui.kmp.transport.cache

import dev.sdui.kmp.transport.cache.internal.Sha256
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * JVM / desktop implementation of [ScreenDiskCache] backed by a flat directory of JSON files.
 *
 * Each cache entry is stored as `<sha256(key)>.json` under [rootDir]; writes go through a
 * `.tmp` file and `ATOMIC_MOVE` so a crash mid-write cannot corrupt the cache (we'd just see
 * the previous entry, or nothing). Reads that fail to parse return `null` — corrupt entries
 * are treated as misses, never as crashes.
 *
 * The default [rootDir] is `${user.home}/.cache/sdui-kmp/screens`, matching XDG-ish defaults
 * for cache data on POSIX systems and a plausible-enough location on Windows.
 */
public class JvmScreenDiskCache(
    private val rootDir: Path = defaultRootDir(),
    private val json: Json = Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScreenDiskCache {

    init {
        Files.createDirectories(rootDir)
    }

    override suspend fun load(key: String): CacheEntry? = withContext(ioDispatcher) {
        val file = fileFor(key)
        runCatching {
            if (!Files.exists(file)) return@withContext null
            val text = Files.readString(file)
            json.decodeFromString(CacheEntry.serializer(), text)
        }.getOrNull()
    }

    override suspend fun store(key: String, entry: CacheEntry): Unit = withContext(ioDispatcher) {
        val file = fileFor(key)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        runCatching {
            Files.createDirectories(rootDir)
            val payload = json.encodeToString(CacheEntry.serializer(), entry)
            Files.writeString(tmp, payload)
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    override suspend fun remove(key: String): Unit = withContext(ioDispatcher) {
        runCatching { Files.deleteIfExists(fileFor(key)) }
        Unit
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        runCatching {
            if (!Files.exists(rootDir)) return@withContext
            Files.list(rootDir).use { stream ->
                stream.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        Unit
    }

    private fun fileFor(key: String): Path = rootDir.resolve(Sha256.hexDigest(key) + ".json")

    public companion object {
        /** Default cache directory: `${user.home}/.cache/sdui-kmp/screens`. */
        public fun defaultRootDir(): Path =
            Paths.get(System.getProperty("user.home") ?: ".", ".cache", "sdui-kmp", "screens")
    }
}
