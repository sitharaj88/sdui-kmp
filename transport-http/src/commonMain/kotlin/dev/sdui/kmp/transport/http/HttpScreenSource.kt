package dev.sdui.kmp.transport.http

import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.runtime.ScreenSource
import dev.sdui.kmp.runtime.ScreenState
import dev.sdui.kmp.transport.cache.CacheEntry
import dev.sdui.kmp.transport.cache.ScreenDiskCache
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fetches a [Screen] from [baseUrl]/[path] via HTTP GET with ETag caching at two layers:
 *
 *  - An **in-memory** cache that is the fast path during the lifetime of the process.
 *  - An optional [diskCache] that survives process death so a cold start can fast-path through
 *    the renderer instead of waiting for a network round trip.
 *
 * On every fetch the source sends `If-None-Match` with the stored ETag (if any). If the
 * server responds `304 Not Modified`, the cached [Screen] is re-emitted without a re-parse.
 *
 * The disk cache is **best-effort**: any failure to load or store is swallowed and the source
 * falls through to the network. Cache misbehavior must never surface as a rendering error.
 *
 * Call [retry] to bypass the cache on the next fetch; useful for pull-to-refresh.
 */
public class HttpScreenSource(
    private val client: HttpClient,
    private val baseUrl: String,
    private val path: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val diskCache: ScreenDiskCache? = null,
) : ScreenSource {
    private val _state: MutableStateFlow<ScreenState> = MutableStateFlow(ScreenState.Loading)
    override val screen: StateFlow<ScreenState> = _state.asStateFlow()

    private var inFlight: Job? = null
    private var cachedEtag: String? = null
    private var cachedScreen: Screen? = null

    init {
        fetch(useCache = true)
    }

    override fun retry() {
        fetch(useCache = false)
    }

    private fun fetch(useCache: Boolean) {
        inFlight?.cancel()
        _state.value = ScreenState.Loading
        inFlight = scope.launch {
            try {
                val url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
                val cacheKey = url

                if (useCache && cachedScreen == null && diskCache != null) {
                    primeFromDisk(cacheKey)
                }

                val response: HttpResponse = client.get(url) {
                    if (useCache) {
                        cachedEtag?.let { header(HttpHeaders.IfNoneMatch, it) }
                    }
                }
                when {
                    response.status == HttpStatusCode.NotModified && cachedScreen != null -> {
                        _state.value = ScreenState.Ready(cachedScreen!!)
                    }
                    response.status.isSuccess() -> {
                        val rawText = response.bodyAsText()
                        val screen = SduiJson.decodeFromString(Screen.serializer(), rawText)
                        val etag = response.headers[HttpHeaders.ETag]
                        if (etag != null) {
                            cachedEtag = etag
                            cachedScreen = screen
                            persistToDisk(cacheKey, etag, rawText)
                        }
                        _state.value = ScreenState.Ready(screen)
                    }
                    else -> {
                        _state.value = ScreenState.Error(
                            HttpStatusException(response.status.value, response.status.description),
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = ScreenState.Error(t)
            }
        }
    }

    private suspend fun primeFromDisk(cacheKey: String) {
        val cache = diskCache ?: return
        val entry = runCatching { cache.load(cacheKey) }.getOrNull() ?: return
        val screen = runCatching {
            SduiJson.decodeFromString(Screen.serializer(), entry.screenJsonBytes.decodeToString())
        }.getOrNull() ?: return
        cachedEtag = entry.etag
        cachedScreen = screen
    }

    private fun persistToDisk(cacheKey: String, etag: String, rawJson: String) {
        val cache = diskCache ?: return
        // We do not stamp wall-clock time here: the transport stays platform-pure (no
        // expect/actual) and downstream readers (TTL eviction, debug surfaces) will get a
        // real timestamp when a future caller threads a Clock into the constructor.
        val entry = CacheEntry(
            etag = etag,
            screenJsonBytes = rawJson.encodeToByteArray(),
            storedAtEpochMs = 0L,
        )
        scope.launch { runCatching { cache.store(cacheKey, entry) } }
    }

    /** Release the coroutine scope. Call this when the source is no longer needed. */
    public fun close() {
        scope.cancel()
    }
}

/** Thrown into [ScreenState.Error] when the server answers a non-success status. */
public class HttpStatusException(
    public val statusCode: Int,
    public val description: String,
) : Exception("HTTP $statusCode $description")

/**
 * Configures an [HttpClientConfig] with the `ContentNegotiation` plugin wired to [SduiJson].
 * Host code installing its own JSON configuration should register [SduiJson] in the same way.
 */
public fun HttpClientConfig<*>.installSduiJson() {
    install(ContentNegotiation) {
        json(SduiJson)
    }
}
