package dev.sdui.kmp.transport.live

import dev.sdui.kmp.protocol.LiveEvent
import kotlinx.serialization.Serializable

/**
 * Wire envelope shared by every cross-process [LiveBus] implementation.
 *
 * The Postgres backend NOTIFY channel is a single-shot string-keyed pipe — every publisher
 * writes one channel and every subscriber reads it, so the envelope carries the [topic]
 * (typically a screen id) alongside the [LiveEvent] payload. Subscribers filter by topic
 * after decode.
 *
 * **Wire compatibility:** treat this exactly like a protocol type — the field names and
 * `@SerialName` discriminators on `LiveEvent` are public wire format. Adding new fields here
 * is fine; renaming or removing existing ones breaks rolling deploys where two versions
 * publish to the same Postgres channel.
 *
 * **Payload-pointer fallback.** Postgres' `NOTIFY` truncates payloads above ~8 KiB, so
 * envelopes whose JSON encoding exceeds [PostgresLivePublisher.MAX_NOTIFY_PAYLOAD_BYTES]
 * are emitted as a [LiveEvent.TreePatchEvent] with an empty patch carrying a single
 * "fetch this screen" pointer node — clients are expected to treat the empty patch as a
 * cue to re-fetch the screen via HTTP. See ADR-0021 for the reasoning.
 */
@Serializable
public data class LiveEnvelope(
    /** Subscription topic the [event] belongs to — typically a screen id like `home`. */
    public val topic: String,
    /** The fan-out payload itself. */
    public val event: LiveEvent,
)
