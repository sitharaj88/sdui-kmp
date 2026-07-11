package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.runtime.LiveSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlin.math.pow
import kotlin.random.Random

/**
 * Capped exponential-backoff schedule (with jitter) used by [WebSocketLiveSource] to space out
 * reconnection attempts after the socket drops.
 *
 * The delay for attempt `n` (0-based) is `initialDelayMillis * multiplier^n`, clamped to
 * [maxDelayMillis], then reduced by up to [jitterRatio] of that value to de-synchronise a fleet
 * of clients that all lost their connection at the same instant (e.g. an instance restart). The
 * attempt counter resets to zero once a connection is successfully established, so a healthy
 * client that briefly blips reconnects promptly rather than inheriting a stale large delay.
 */
public data class ReconnectPolicy(
    /** Delay before the first reconnect attempt, in milliseconds. */
    public val initialDelayMillis: Long = 500L,
    /** Upper bound the exponential delay is clamped to, in milliseconds. */
    public val maxDelayMillis: Long = 30_000L,
    /** Factor the delay grows by after each consecutive failed attempt. */
    public val multiplier: Double = 2.0,
    /** Fraction of the computed delay that may be shaved off as random jitter, in `[0.0, 1.0]`. */
    public val jitterRatio: Double = 0.5,
) {
    init {
        require(initialDelayMillis >= 0L) { "initialDelayMillis must be non-negative" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within [0.0, 1.0]" }
    }

    /**
     * Delay in milliseconds before the given 0-based [attempt], drawing jitter from [random]
     * (a supplier of values in `[0.0, 1.0)`, injectable for deterministic tests).
     */
    internal fun delayFor(attempt: Int, random: () -> Double): Long {
        val grown = initialDelayMillis.toDouble() * multiplier.pow(attempt.coerceAtLeast(0))
        val capped = grown.coerceAtMost(maxDelayMillis.toDouble())
        val jitter = capped * jitterRatio * random()
        return (capped - jitter).toLong().coerceIn(0L, maxDelayMillis)
    }
}

/**
 * [LiveSource] that streams [LiveEvent] JSON frames over a WebSocket, reconnecting automatically
 * with capped exponential backoff after any close or failure until [stop] is called.
 *
 * Each text frame is parsed via [SduiJson]; malformed frames are dropped (reported through
 * [onParseError]) rather than killing the connection. When the socket drops, the source waits per
 * [reconnect] and re-establishes; connection failures are reported through [onConnectionError]
 * rather than swallowed, and never surface as a crash.
 *
 * Because a live bus keeps no history (see [LiveBus]), any events published while the client was
 * disconnected are lost. To close that gap, [onReconnect] is invoked before each (re)connection so
 * the consumer can HTTP re-fetch the canonical screen and then resume streaming from "now". It
 * fires on the very first connection too; the default is a no-op, so existing callers are
 * unaffected.
 *
 * [start] / [stop] are explicit and idempotent — `SduiHost`'s DisposableEffect coordinates
 * lifetime. Calling [start] while already running is a no-op; [stop] cancels the reconnect loop
 * and closes the current socket, and a later [start] resumes cleanly.
 *
 * The [HttpClient] must have [WebSockets] installed. Use [installWebSockets] for the default
 * configuration.
 */
public class WebSocketLiveSource internal constructor(
    private val onParseError: (String, Throwable) -> Unit,
    private val onReconnect: suspend () -> Unit,
    private val onConnectionError: (Throwable) -> Unit,
    private val reconnect: ReconnectPolicy,
    private val scope: CoroutineScope,
    private val connect: suspend () -> WebSocketSession,
    private val delayFn: suspend (Long) -> Unit,
    private val random: () -> Double,
) : LiveSource {

    /**
     * @param client WebSocket-capable client (see [installWebSockets]).
     * @param url `ws(s)://` endpoint streaming [LiveEvent] JSON frames.
     * @param onParseError reports a frame that failed to decode; the stream continues.
     * @param scope host scope the reconnect loop runs in.
     * @param onReconnect resync hook invoked before each (re)connection so the consumer can
     *   re-fetch the canonical screen before the live stream resumes.
     * @param onConnectionError reports a failed connection attempt before the backoff delay.
     * @param reconnect backoff schedule between reconnection attempts.
     */
    public constructor(
        client: HttpClient,
        url: String,
        onParseError: (String, Throwable) -> Unit = { _, _ -> },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        onReconnect: suspend () -> Unit = {},
        onConnectionError: (Throwable) -> Unit = {},
        reconnect: ReconnectPolicy = ReconnectPolicy(),
    ) : this(
        onParseError = onParseError,
        onReconnect = onReconnect,
        onConnectionError = onConnectionError,
        reconnect = reconnect,
        scope = scope,
        connect = { client.webSocketSession(urlString = url) },
        delayFn = { delay(it) },
        random = { Random.nextDouble() },
    )

    private val _events = MutableSharedFlow<LiveEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<LiveEvent> = _events.asSharedFlow()

    private var session: WebSocketSession? = null
    private var pump: Job? = null

    override fun start() {
        if (pump?.isActive == true) return
        pump = scope.launch { runLoop() }
    }

    override fun stop() {
        pump?.cancel()
        pump = null
        val s = session
        session = null
        if (s != null) {
            scope.launch { s.close() }
        }
    }

    /** Release the coroutine scope. Call after [stop] when the source is no longer needed. */
    public fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                onReconnect()
                val s = connect()
                session = s
                // A successful connection means the endpoint is reachable: reset the schedule so a
                // brief blip does not inherit a large delay accumulated from earlier failures.
                attempt = 0
                streamFrames(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onConnectionError(e)
            }
            if (!currentCoroutineContext().isActive) break
            val wait = reconnect.delayFor(attempt, random)
            attempt += 1
            delayFn(wait)
        }
    }

    private suspend fun streamFrames(s: WebSocketSession) {
        try {
            for (frame in s.incoming) {
                if (frame is Frame.Text) {
                    emitFrame(frame.readText())
                }
            }
        } finally {
            session = null
        }
    }

    private suspend fun emitFrame(raw: String) {
        try {
            _events.emit(SduiJson.decodeFromString(LiveEvent.serializer(), raw))
        } catch (e: SerializationException) {
            onParseError(raw, e)
        }
    }
}

/** Install the WebSocket plugin on an [HttpClient] configuration. */
public fun io.ktor.client.HttpClientConfig<*>.installWebSockets() {
    install(WebSockets)
}
