package dev.sdui.kmp.transport.cache

import dev.sdui.kmp.transport.cache.internal.Sha256
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json

/**
 * Wasm/JS implementation of [ScreenDiskCache] backed by [localStorage].
 *
 * IndexedDB is the principled long-term answer (async, larger quota, structured storage), but
 * `localStorage` is enough for v1: synchronous, ubiquitous, and screens fit comfortably below
 * the typical 5 MiB origin quota. Keys are namespaced with [KEY_PREFIX] so the cache cannot
 * collide with unrelated app state.
 *
 * Note: errors from a full / disabled `localStorage` are caught and treated as misses, matching
 * the best-effort contract.
 */
public class WasmLocalStorageScreenDiskCache(
    private val json: Json = Json,
) : ScreenDiskCache {

    override suspend fun load(key: String): CacheEntry? {
        return runCatching {
            val raw = localStorage.getItem(storageKey(key)) ?: return null
            json.decodeFromString(CacheEntry.serializer(), raw)
        }.getOrNull()
    }

    override suspend fun store(key: String, entry: CacheEntry) {
        runCatching {
            val payload = json.encodeToString(CacheEntry.serializer(), entry)
            localStorage.setItem(storageKey(key), payload)
        }
    }

    override suspend fun remove(key: String) {
        runCatching { localStorage.removeItem(storageKey(key)) }
    }

    override suspend fun clear() {
        runCatching {
            val toRemove = mutableListOf<String>()
            for (i in 0 until localStorage.length) {
                val k = localStorage.key(i) ?: continue
                if (k.startsWith(KEY_PREFIX)) toRemove += k
            }
            for (k in toRemove) localStorage.removeItem(k)
        }
    }

    private fun storageKey(key: String): String = KEY_PREFIX + Sha256.hexDigest(key)

    private companion object {
        const val KEY_PREFIX: String = "sdui-kmp:screens:"
    }
}
