package dev.sdui.kmp.transport.cache

/**
 * Disk-backed cache for HTTP-loaded screens, keyed by an opaque [String] (typically the request
 * URL or path). The contract is intentionally tiny: implementations only need to persist bytes,
 * not understand them.
 *
 * Implementations are **best-effort**. Callers (notably the HTTP transport) MUST tolerate any
 * implementation throwing or returning `null`: a cache failure must never bubble up as a
 * rendering error. The transport falls through to the network and re-populates the cache on
 * the next successful response.
 *
 * Implementations are expected to be safe to call from any coroutine context; long-running I/O
 * should be dispatched onto the appropriate platform IO dispatcher inside [load] / [store].
 */
public interface ScreenDiskCache {
    /** Returns the cached entry for [key], or `null` if absent / corrupted / unreadable. */
    public suspend fun load(key: String): CacheEntry?

    /** Persists [entry] under [key]. Existing entries are overwritten. */
    public suspend fun store(key: String, entry: CacheEntry)

    /** Removes the entry for [key]. No-op if absent. */
    public suspend fun remove(key: String)

    /** Clears every entry. Used on user logout / cache eviction. */
    public suspend fun clear()
}
