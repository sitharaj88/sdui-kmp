package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An ordered list of structural operations to apply to a [Screen] tree.
 *
 * Operations reference nodes by [NodeId] — not by JSON pointer — because node ids are stable
 * across re-renders and survive additive protocol evolution. The runtime maintains an
 * `Map<NodeId, UiNode>` index so patch application is O(n) in the number of ops, not in
 * tree size.
 */
@Serializable
public data class TreePatch(public val ops: List<PatchOp>)

/** One structural change inside a [TreePatch]. */
@Serializable
public sealed interface PatchOp {
    /** Replace the node with id [nodeId] by [node]. Subtree is replaced wholesale. */
    @Serializable
    @SerialName("replace")
    public data class Replace(public val nodeId: NodeId, public val node: UiNode) : PatchOp

    /** Append [nodes] as children of the [Container] with id [parentId]. */
    @Serializable
    @SerialName("append")
    public data class Append(public val parentId: NodeId, public val nodes: List<UiNode>) : PatchOp

    /** Remove every node whose id appears in [nodeIds]. */
    @Serializable
    @SerialName("remove")
    public data class Remove(public val nodeIds: List<NodeId>) : PatchOp

    /**
     * Inert sentinel decoded when the `type` discriminator names a [PatchOp] this client does
     * not recognize. Patch engines skip it (leaving the tree unchanged) rather than throwing, so
     * a newer structural op can never blank the screen on an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : PatchOp
}
