package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.TreePatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.postgresql.PGConnection
import java.io.Closeable
import java.sql.Connection
import javax.sql.DataSource
import kotlin.math.min

/**
 * Cross-process [LiveBus] backed by Postgres `LISTEN` / `NOTIFY`.
 *
 * **Why Postgres pub/sub.** Both sample-server and studio-server already require Postgres for
 * their primary data plane (sessions, screen versions, audit log). Adding `LISTEN sdui_live`
 * to the same cluster means cross-JVM live publishing works without a new infrastructure
 * dependency (no Redis, no NATS, no Kafka). ADR-0021 captures the trade-offs.
 *
 * **Connection topology.** Two distinct connection pathways:
 *
 * - **Publish path** ([publish]) borrows a short-lived connection from the supplied
 *   [DataSource], executes a single `NOTIFY sdui_live, <payload>` statement, and returns
 *   the connection to the pool. Hot-loop scaling is bounded by the pool's capacity.
 * - **Subscribe path** ([subscribe]) returns a cold flow from a process-local [SharedFlow]
 *   fed by a single **dedicated** long-lived connection that runs `LISTEN sdui_live` and
 *   polls `getNotifications(timeout)` in a loop. The dedicated connection is **not**
 *   returned to the pool — `LISTEN` registrations are per-connection state and HikariCP
 *   would happily hand the connection to another caller, dropping our subscription.
 *
 * **Reconnect.** The listener loop survives transient DB outages — on any failure (probe
 * SQL throws, `getNotifications` throws, the server kills the session) we close the broken
 * connection, sleep with exponential backoff (capped at [MAX_BACKOFF_MILLIS]), and open a
 * fresh listener. **No replay** of missed events: by [LiveBus]'s contract, a live bus is
 * not an event store, so a backoff window means subscribers miss whatever the studio
 * published during it. Clients catch up via HTTP screen fetch on the next subscription.
 *
 * **8 KiB payload limit (Postgres).** `NOTIFY` truncates payloads above ~8000 bytes with
 * an error; an over-sized envelope is silently rewritten into a "fetch this screen"
 * pointer — a [LiveEvent.TreePatchEvent] carrying an **empty** [TreePatch]. The empty
 * patch is a no-op for runtime apply but is the cue downstream code (the bridge in
 * `:samples:sample-server` / `:studio-server`) interprets as "the canonical body is the
 * source of truth — re-fetch over HTTP and re-publish in chunks if you need streaming."
 * No protocol surface changes — the semantics live entirely in the transport layer. See
 * ADR-0021 for the contract.
 *
 * **Coroutine discipline.** Per CLAUDE.md / KICKOFF_PROMPT.md the publisher MUST NOT block
 * the main coroutine context: every JDBC call is wrapped in `withContext(Dispatchers.IO)`,
 * and the listener coroutine runs on a dedicated [SupervisorJob] so a listener crash does
 * not propagate into the host's lifecycle scope.
 *
 * @param dataSource pooled HikariCP-style data source for the publish path. The data
 *   source is **not** owned by this class — closing the publisher does not close it.
 * @param scope outer coroutine scope. The listener launches a child supervised by
 *   the publisher's own [SupervisorJob] so cancelling [scope] cancels the listener but a
 *   listener crash does not cancel anything else in [scope].
 * @param dispatcher I/O dispatcher for blocking JDBC calls. Override only in tests.
 * @param onPayloadTooLarge invoked on the publish path when [event] would exceed the
 *   `NOTIFY` ceiling. Hosts can use this to re-publish in chunks or trigger a recipient
 *   refresh signal. Defaults to a no-op so the empty-patch fallback alone reaches subscribers.
 * @param onListenerError invoked when the listener loop catches a transient failure (DB
 *   went away, malformed payload). Hosts that want logging plug in their SLF4J adapter.
 */
@Suppress("LongParameterList")
public class PostgresLivePublisher(
    private val dataSource: DataSource,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    @Suppress("InjectDispatcher")
    private val dispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    private val onListenerError: (Throwable) -> Unit = {},
    private val onPayloadTooLarge: (topic: String, payloadBytes: Int) -> Unit = { _, _ -> },
    private val notificationTimeoutMillis: Int = NOTIFICATION_POLL_TIMEOUT_MILLIS,
    private val initialBackoffMillis: Long = INITIAL_BACKOFF_MILLIS,
    private val maxBackoffMillis: Long = MAX_BACKOFF_MILLIS,
) : LiveBus, Closeable {

    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + supervisor)

    private val incoming = MutableSharedFlow<LiveEnvelope>(
        replay = 0,
        extraBufferCapacity = LISTENER_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Test seam: read-only view of the listener-fed shared flow. */
    internal val envelopes: Flow<LiveEnvelope> = incoming.asSharedFlow()

    @Volatile
    private var listenerJob: Job? = null

    init {
        startListener()
    }

    private fun startListener() {
        if (listenerJob != null) return
        listenerJob = ownScope.launch(dispatcher) {
            runListenerLoop()
        }
    }

    /** Reconnect-aware long-lived listener loop. */
    private suspend fun runListenerLoop() {
        var backoff = initialBackoffMillis
        while (ownScope.isActive) {
            val connection = openListenerConnection()
            if (connection == null) {
                delay(backoff)
                backoff = min(backoff * BACKOFF_MULTIPLIER, maxBackoffMillis)
                continue
            }
            // Successful connect resets the backoff window — a single transient blip should
            // not penalise the next legitimate disconnect with a 30 s wait.
            backoff = initialBackoffMillis
            try {
                connection.use { runListenerOnConnection(it) }
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                onListenerError(t)
                delay(backoff)
                backoff = min(backoff * BACKOFF_MULTIPLIER, maxBackoffMillis)
            }
        }
    }

    /** Open a fresh dedicated connection and run `LISTEN`. Returns null on failure. */
    private fun openListenerConnection(): Connection? = try {
        val c = dataSource.connection
        c.autoCommit = true
        c.createStatement().use { stmt ->
            stmt.execute("LISTEN $CHANNEL")
        }
        c
    } catch (
        @Suppress("TooGenericExceptionCaught") t: Throwable,
    ) {
        onListenerError(t)
        null
    }

    /**
     * Pump `getNotifications(timeoutMillis)` from [connection] into [incoming]. Returns when
     * the connection is closed or the coroutine is cancelled.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun runListenerOnConnection(connection: Connection) {
        val pgConnection = connection.unwrap(PGConnection::class.java)
        while (ownScope.isActive && !connection.isClosed) {
            val notifications = pgConnection.getNotifications(notificationTimeoutMillis)
            if (notifications.isNullOrEmpty()) continue
            for (n in notifications) {
                val parameter = n.parameter ?: continue
                val envelope = decodeEnvelope(parameter) ?: continue
                incoming.emit(envelope)
            }
        }
    }

    private fun decodeEnvelope(payload: String): LiveEnvelope? = try {
        SduiJson.decodeFromString(LiveEnvelope.serializer(), payload)
    } catch (e: SerializationException) {
        onListenerError(e)
        null
    }

    override suspend fun publish(topic: String, event: LiveEvent) {
        val envelope = LiveEnvelope(topic = topic, event = event)
        val encoded = SduiJson.encodeToString(LiveEnvelope.serializer(), envelope)
        val sizeBytes = encoded.toByteArray(Charsets.UTF_8).size
        val payload = if (sizeBytes > MAX_NOTIFY_PAYLOAD_BYTES) {
            onPayloadTooLarge(topic, sizeBytes)
            encodePointerFallback(topic)
        } else {
            encoded
        }
        // NOTIFY can't be parameterised positionally — Postgres' grammar requires a string
        // literal. Single-quote the payload and double any embedded single quotes so a
        // payload containing `'` does not break the statement.
        val escaped = payload.replace("'", "''")
        withContext(dispatcher) {
            dataSource.connection.use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { stmt ->
                    stmt.execute("NOTIFY $CHANNEL, '$escaped'")
                }
            }
        }
    }

    /**
     * Encode the empty-patch pointer fallback. Subscribers see a [LiveEvent.TreePatchEvent]
     * with `ops = emptyList()` — applying an empty patch is a no-op at the runtime layer,
     * but the bridge in the host server treats it as a refresh signal and re-fetches the
     * canonical screen body via HTTP before broadcasting a chunked replacement. See
     * ADR-0021.
     */
    private fun encodePointerFallback(topic: String): String {
        val pointer = LiveEnvelope(
            topic = topic,
            event = LiveEvent.TreePatchEvent(patch = TreePatch(ops = emptyList())),
        )
        return SduiJson.encodeToString(LiveEnvelope.serializer(), pointer)
    }

    override fun subscribe(topic: String): Flow<LiveEvent> =
        incoming.filter { it.topic == topic }.map { it.event }

    /** Stop the listener and cancel the bridge scope. The supplied [DataSource] is left intact. */
    override fun close() {
        listenerJob?.cancel()
        listenerJob = null
        ownScope.cancel()
    }

    public companion object {
        /** The single Postgres notification channel name shared by every sdui-kmp publisher. */
        public const val CHANNEL: String = "sdui_live"

        /**
         * Postgres' documented hard limit on `NOTIFY` payload size — 8000 bytes (the docs
         * say "less than 8000 bytes", but the actual limit is the `MAX_PAYLOAD_LEN`
         * compile-time constant of 8000). We stay just under so a UTF-8 multi-byte tail
         * doesn't accidentally cross the boundary.
         */
        public const val MAX_NOTIFY_PAYLOAD_BYTES: Int = 7900

        /** Listener `getNotifications` timeout. Long enough to keep CPU idle; short enough that cancel propagates fast. */
        internal const val NOTIFICATION_POLL_TIMEOUT_MILLIS: Int = 500

        /** Base reconnect delay. Doubles up to [MAX_BACKOFF_MILLIS] on repeated failures. */
        internal const val INITIAL_BACKOFF_MILLIS: Long = 250L

        /** Cap on reconnect delay — 30 s per the spec. */
        internal const val MAX_BACKOFF_MILLIS: Long = 30_000L

        /** Backoff growth factor between consecutive failures. */
        internal const val BACKOFF_MULTIPLIER: Long = 2L

        /** Listener-side buffer; same drop-oldest contract as [WebSocketLiveSource]. */
        internal const val LISTENER_BUFFER_CAPACITY: Int = 64
    }
}
