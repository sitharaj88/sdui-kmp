package dev.sdui.kmp.transport.cache

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Exercises the real [JvmScreenDiskCache] against a `Files.createTempDirectory` root. */
class JvmScreenDiskCacheTest {

    private val json = Json

    @Test
    fun store_load_remove_clear_round_trip() = runTest {
        val tmp = Files.createTempDirectory("sdui-cache-test")
        val cache = JvmScreenDiskCache(rootDir = tmp, json = json)

        val entry = CacheEntry(
            etag = "\"v1\"",
            screenJsonBytes = "{\"node\":1}".encodeToByteArray(),
            storedAtEpochMs = 42L,
        )

        cache.store("https://example.com/screens/home", entry)
        val loaded = cache.load("https://example.com/screens/home")
        assertNotNull(loaded)
        assertEquals(entry.etag, loaded.etag)
        assertContentEquals(entry.screenJsonBytes, loaded.screenJsonBytes)
        assertEquals(entry.storedAtEpochMs, loaded.storedAtEpochMs)

        cache.remove("https://example.com/screens/home")
        assertNull(cache.load("https://example.com/screens/home"))

        cache.store("a", CacheEntry("e1", ByteArray(1), 1L))
        cache.store("b", CacheEntry("e2", ByteArray(1), 2L))
        cache.clear()
        assertNull(cache.load("a"))
        assertNull(cache.load("b"))
    }

    @Test
    fun load_returns_null_when_file_is_corrupt() = runTest {
        val tmp = Files.createTempDirectory("sdui-cache-corrupt")
        val cache = JvmScreenDiskCache(rootDir = tmp, json = json)
        // Write garbage at the path the cache will look at.
        cache.store("k", CacheEntry("e", ByteArray(0), 0L))
        // Find the file and corrupt it.
        val files = Files.list(tmp).use { it.toList() }
        val target = files.first { it.fileName.toString().endsWith(".json") }
        Files.writeString(target, "this-is-not-json")
        assertNull(cache.load("k"))
    }
}
