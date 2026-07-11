package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.LiveEvent
import kotlinx.coroutines.flow.Flow

/**
 * Reactive source of [LiveEvent]s — typically a WebSocket connection, SSE stream, or a
 * test-double emitting events in a known order.
 *
 * Lifecycle: implementations connect on [start] and release on [stop]. `SduiHost` wires this
 * via a `DisposableEffect` so the connection follows composition.
 */
public interface LiveSource {
    public val events: Flow<LiveEvent>
    public fun start()
    public fun stop()
}
