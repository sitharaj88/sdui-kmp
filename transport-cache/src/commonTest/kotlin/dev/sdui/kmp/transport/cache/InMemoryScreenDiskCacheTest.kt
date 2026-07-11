package dev.sdui.kmp.transport.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip contract test for the [ScreenDiskCache] interface, exercised against a trivial
 * in-memory fake. Real platform implementations get their own per-source-set tests.
 */
class InMemoryScreenDiskCacheTest {

    private class InMemoryCache : ScreenDiskCache {
        private val map = mutableMapOf<String, CacheEntry>()
        override suspend fun load(key: String): CacheEntry? = map[key]
        override suspend fun store(key: String, entry: CacheEntry) { map[key] = entry }
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun clear() { map.clear() }
    }

    @Test
    fun store_then_load_returns_equivalent_entry() = runTest {
        val cache = InMemoryCache()
        val entry = CacheEntry(
            etag = "W/\"abc123\"",
            screenJsonBytes = "{\"hello\":\"world\"}".encodeToByteArray(),
            storedAtEpochMs = 1_700_000_000_000L,
        )

        cache.store("key1", entry)
        val loaded = cache.load("key1")

        assertEquals(entry.etag, loaded?.etag)
        assertContentEquals(entry.screenJsonBytes, loaded?.screenJsonBytes)
        assertEquals(entry.storedAtEpochMs, loaded?.storedAtEpochMs)
    }

    @Test
    fun load_returns_null_for_missing_key() = runTest {
        val cache = InMemoryCache()
        assertNull(cache.load("absent"))
    }

    @Test
    fun remove_deletes_entry() = runTest {
        val cache = InMemoryCache()
        cache.store("k", CacheEntry("e", ByteArray(0), 0L))
        cache.remove("k")
        assertNull(cache.load("k"))
    }

    @Test
    fun clear_removes_all_entries() = runTest {
        val cache = InMemoryCache()
        cache.store("a", CacheEntry("e1", ByteArray(0), 0L))
        cache.store("b", CacheEntry("e2", ByteArray(0), 0L))
        cache.clear()
        assertNull(cache.load("a"))
        assertNull(cache.load("b"))
    }
}
