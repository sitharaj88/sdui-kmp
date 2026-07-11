package dev.sdui.kmp.transport.cache

import dev.sdui.kmp.transport.cache.internal.Sha256
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy

/**
 * iOS implementation of [ScreenDiskCache] backed by `NSCachesDirectory / sdui-kmp/screens/`.
 *
 * The system may purge `NSCachesDirectory` under storage pressure; that's correct behavior for
 * a best-effort screen cache. Entries are stored as `<sha256(key)>.json` files; reads that
 * fail to decode (corrupt / partial / missing) return `null`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class IosScreenDiskCache(
    private val json: Json = Json,
) : ScreenDiskCache {

    private val rootUrl: NSURL = run {
        val fm = NSFileManager.defaultManager
        val cachesUrl = fm.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) ?: error("NSCachesDirectory unavailable")
        val dir = cachesUrl.URLByAppendingPathComponent("sdui-kmp")
            ?.URLByAppendingPathComponent("screens")
            ?: error("could not construct cache URL")
        fm.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir
    }

    override suspend fun load(key: String): CacheEntry? = withContext(Dispatchers.Default) {
        runCatching {
            val url = urlFor(key) ?: return@withContext null
            val data = NSData.dataWithContentsOfURL(url) ?: return@withContext null
            val bytes = data.toByteArray()
            val text = bytes.decodeToString()
            json.decodeFromString(CacheEntry.serializer(), text)
        }.getOrNull()
    }

    override suspend fun store(key: String, entry: CacheEntry): Unit = withContext(Dispatchers.Default) {
        runCatching {
            val url = urlFor(key) ?: return@withContext
            val payload = json.encodeToString(CacheEntry.serializer(), entry)
            val bytes = payload.encodeToByteArray()
            val data = bytes.toNSData()
            data.writeToURL(url, atomically = true)
        }
        Unit
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.Default) {
        runCatching {
            val url = urlFor(key) ?: return@withContext
            NSFileManager.defaultManager.removeItemAtURL(url, error = null)
        }
        Unit
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        runCatching {
            val fm = NSFileManager.defaultManager
            fm.removeItemAtURL(rootUrl, error = null)
            fm.createDirectoryAtURL(rootUrl, withIntermediateDirectories = true, attributes = null, error = null)
        }
        Unit
    }

    private fun urlFor(key: String): NSURL? =
        rootUrl.URLByAppendingPathComponent(Sha256.hexDigest(key) + ".json")
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    if (this@toNSData.isEmpty()) return@memScoped NSData()
    val ptr = allocArrayOf(this@toNSData)
    NSData.dataWithBytes(ptr, this@toNSData.size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val out = ByteArray(length)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return out
}
