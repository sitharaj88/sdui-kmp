package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.transport.live.LiveBus
import kotlinx.coroutines.CancellationException

/**
 * [PublishNotifier] that fans a freshly-published screen out to every subscriber of the
 * matching live topic by publishing a [LiveEvent.TreePatchEvent] onto the shared [bus].
 * The wire-format encoding wraps a single [PatchOp.Replace] keyed off the new root node's
 * id — this is the simplest "swap the whole tree" patch the runtime knows how to apply, and
 * covers the M_S3 hot-reload contract without requiring server-side diffing.
 *
 * **Why the bus, not the local WebSocket registry.** Publishing onto the [LiveBus] is the
 * single delivery path: this JVM's own [dev.sdui.kmp.transport.live.DynamicLiveBusBridge]
 * (Postgres `NOTIFY` reaches the emitting session too; the in-process bus emits to every
 * subscriber) fans the event out to local sockets, and **every other** studio/fan-out JVM
 * connected to the same bus does the same for its sockets. Broadcasting straight to a local
 * [dev.sdui.kmp.transport.live.WebSocketLivePublisher] would only ever reach clients pinned
 * to the publishing instance — behind any load balancer, most clients would miss most
 * publishes.
 *
 * Decoding is best-effort: a malformed [body] is logged silently and dropped so a
 * notifier failure never rolls back the publish (per [PublishNotifier]'s "MUST NOT throw"
 * contract). The [bus] publish is likewise wrapped in [runCatching] so a transient bus
 * failure (a DB blip on the Postgres backend) can never propagate into the publish route.
 * Encoding `body` to JSON is round-trip safe because it is the canonical
 * `screen_versions.body_json` string.
 */
public class WebSocketPublishNotifier(
    private val bus: LiveBus,
) : PublishNotifier {

    override suspend fun screenPublished(screenId: String, version: Int, body: String) {
        val screen = runCatching {
            SduiJson.decodeFromString(Screen.serializer(), body)
        }.getOrNull() ?: return
        val patch = TreePatch(
            ops = listOf(PatchOp.Replace(nodeId = screen.root.id, node = screen.root)),
        )
        try {
            bus.publish(screenId, LiveEvent.TreePatchEvent(patch))
        } catch (e: CancellationException) {
            // Never swallow cooperative cancellation — let structured concurrency unwind.
            throw e
        } catch (_: Throwable) {
            // A transient bus failure must not roll back the (already-committed) publish, per the
            // PublishNotifier "MUST NOT throw" contract. The screen is served correctly over HTTP;
            // only the live hot-reload push is dropped.
        }
    }
}
