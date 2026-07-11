package dev.sdui.kmp.transport.cache

import android.content.Context
import dev.sdui.kmp.transport.cache.internal.Sha256
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Android implementation of [ScreenDiskCache] backed by `Context.cacheDir / "sdui-kmp/screens"`.
 *
 * The OS may evict files in `cacheDir` under storage pressure, which is exactly the desired
 * semantics for a screen cache: a missing entry just means we re-fetch from the network.
 *
 * Each entry is a `<sha256(key)>.json` file containing a serialized [CacheEntry]. Writes go
 * through a `.tmp` and atomic rename so a crash cannot leave a half-written file.
 */
public class AndroidScreenDiskCache(
    context: Context,
    private val json: Json = Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScreenDiskCache {

    private val rootDir: File = File(context.cacheDir, "sdui-kmp/screens").also { it.mkdirs() }

    override suspend fun load(key: String): CacheEntry? = withContext(ioDispatcher) {
        val file = fileFor(key)
        runCatching {
            if (!file.exists()) return@withContext null
            val text = file.readText()
            json.decodeFromString(CacheEntry.serializer(), text)
        }.getOrNull()
    }

    override suspend fun store(key: String, entry: CacheEntry): Unit = withContext(ioDispatcher) {
        val file = fileFor(key)
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            rootDir.mkdirs()
            val payload = json.encodeToString(CacheEntry.serializer(), entry)
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        Unit
    }

    override suspend fun remove(key: String): Unit = withContext(ioDispatcher) {
        runCatching { fileFor(key).delete() }
        Unit
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        runCatching {
            rootDir.listFiles()?.forEach { it.delete() }
        }
        Unit
    }

    private fun fileFor(key: String): File = File(rootDir, Sha256.hexDigest(key) + ".json")
}
