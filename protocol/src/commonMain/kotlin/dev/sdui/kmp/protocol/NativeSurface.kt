package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A "typed escape hatch" — a protocol node that delegates its rendering to a platform-specific
 * factory registered by [kind].
 *
 * Native surfaces are **first-class protocol citizens**: they carry typed config, state
 * bindings, and event handlers just like any other widget, and they fall back through the
 * normal [UiNode.fallback] path when a client has no factory registered for their [kind].
 * This is how a ten-year framework ships new capabilities without changing the core protocol.
 *
 * [kind] strings are namespaced (`sdui.map`, `sdui.player`, `sdui.biometric`) to avoid
 * collisions with host-app additions. A client that does not recognize a kind renders
 * [fallback]; a client with a factory whose `handledVersions` excludes [since] does the same.
 */
@Serializable
@SerialName("native")
public data class NativeSurface(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val kind: String,
    public val config: JsonObject = JsonObject(emptyMap()),
    public val bindings: Map<String, StatePath> = emptyMap(),
    public val events: Map<String, List<Action>> = emptyMap(),
    public val a11y: A11y? = null,
) : Leaf
