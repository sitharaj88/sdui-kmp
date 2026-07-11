package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Single-process [LiveBus] backed by a [MutableSharedFlow]. Used by tests and by the
 * dev wiring where the studio and sample-server run as one JVM. Crash-safe by virtue of
 * doing nothing — events not yet drained are lost on process death, just like Postgres
 * `NOTIFY` events not yet pulled from a `LISTEN`ing connection.
 *
 * Buffer policy: 64 events, drop-oldest-on-overflow. Same shape as [WebSocketLiveSource]'s
 * client-side buffer — a slow subscriber loses old events instead of blocking the publisher.
 */
public class InProcessLiveBus : LiveBus {

    private val sink = MutableSharedFlow<LiveEnvelope>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun publish(topic: String, event: LiveEvent) {
        sink.emit(LiveEnvelope(topic = topic, event = event))
    }

    override fun subscribe(topic: String): Flow<LiveEvent> =
        sink.filter { it.topic == topic }.map { it.event }

    private companion object {
        const val BUFFER_CAPACITY: Int = 64
    }
}
