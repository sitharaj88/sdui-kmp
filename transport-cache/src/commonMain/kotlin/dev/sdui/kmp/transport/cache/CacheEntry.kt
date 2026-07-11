package dev.sdui.kmp.transport.cache

import kotlinx.serialization.Serializable

/**
 * One cached `Screen` payload as it sat on the wire, plus the metadata needed to revalidate it.
 *
 * The cache is **content-agnostic**: it does not parse [screenJsonBytes]. That stays the
 * responsibility of [dev.sdui.kmp.protocol.SduiJson] at the call site, which keeps this module
 * free of any forward dependency on protocol shapes that may evolve.
 *
 * `equals` / `hashCode` are intentionally identity-based on [etag] and [storedAtEpochMs]; the
 * byte array is compared by reference so we do not pay an O(n) compare on every cache miss.
 * Tests that need value-equality on bytes should compare [screenJsonBytes] explicitly.
 */
@Serializable
public data class CacheEntry(
    /** The HTTP `ETag` we will send back in `If-None-Match` on the next request. */
    public val etag: String,
    /** Raw JSON bytes of the `Screen` body, exactly as the server emitted it. */
    public val screenJsonBytes: ByteArray,
    /** Epoch milliseconds when this entry was written. Useful for TTL eviction policies. */
    public val storedAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CacheEntry) return false
        if (etag != other.etag) return false
        if (storedAtEpochMs != other.storedAtEpochMs) return false
        if (!screenJsonBytes.contentEquals(other.screenJsonBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = etag.hashCode()
        result = 31 * result + screenJsonBytes.contentHashCode()
        result = 31 * result + storedAtEpochMs.hashCode()
        return result
    }
}
