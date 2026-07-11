package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Server-pushed event delivered over a live transport (WebSocket, SSE).
 *
 * Two broad shapes: state-level updates that re-resolve existing bindings without changing
 * the tree structure ([StateUpdate]), and tree-level patches that add/remove/replace nodes
 * ([TreePatchEvent]).
 */
@Serializable
public sealed interface LiveEvent {
    /** Apply [updates] to the scoped state store. Every widget bound to these paths recomposes. */
    @Serializable
    @SerialName("state_update")
    public data class StateUpdate(public val updates: Map<StatePath, JsonElement>) : LiveEvent

    /** Apply [patch] to the current screen tree. */
    @Serializable
    @SerialName("tree_patch")
    public data class TreePatchEvent(public val patch: TreePatch) : LiveEvent

    /**
     * Inert sentinel decoded when the `type` discriminator names a [LiveEvent] this client does
     * not recognize. Hosts ignore it rather than throwing, so a newer live-event kind can never
     * break the live stream on an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : LiveEvent
}
