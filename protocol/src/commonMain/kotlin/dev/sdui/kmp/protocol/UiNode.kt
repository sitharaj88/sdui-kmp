package dev.sdui.kmp.protocol

import kotlinx.serialization.Serializable

/**
 * Root of every element in a [Screen] tree.
 *
 * Every node has a stable [id], an introduction [since] version, and an optional [fallback]
 * tree used when the client cannot render this node (either because its schema version is
 * unsupported or its discriminator is unknown).
 *
 * Concrete subtypes are defined in this module and each carry an explicit `@SerialName`. The
 * sealed hierarchy makes `when` branches in renderers exhaustive by construction.
 */
@Serializable
public sealed interface UiNode {
    public val id: NodeId
    public val since: SchemaVersion
    public val fallback: UiNode?
}

/** A [UiNode] that holds an ordered list of children. */
@Serializable
public sealed interface Container : UiNode {
    public val children: List<UiNode>
}

/** A [UiNode] with no children. */
@Serializable
public sealed interface Leaf : UiNode

/**
 * Sentinel decoded when the JSON `type` discriminator is not registered for this client.
 *
 * Emitted only by the client-side decoder via [SduiSerializersModule]'s default fallback; servers
 * must not produce this type. Renderers treat [UnknownUiNode] as "render [fallback] if present,
 * otherwise render nothing" — this is non-negotiable from
 * [VISION.md](VISION.md)'s third non-negotiable.
 */
@Serializable
@kotlinx.serialization.SerialName("__unknown__")
public data class UnknownUiNode(
    override val id: NodeId = NodeId(""),
    override val since: SchemaVersion = SchemaVersion(0),
    override val fallback: UiNode? = null,
    /** The original `type` discriminator the client could not resolve. */
    public val originalType: String = "",
) : Leaf
