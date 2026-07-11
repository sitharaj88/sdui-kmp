package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Container
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.MAX_UI_TREE_DEPTH
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.UnknownUiNode

/**
 * Applies [patch] to [this] and returns the resulting screen.
 *
 * Ops fire in the order they appear; later ops see the effect of earlier ones. Unknown or
 * unresolvable targets (replace/append against a NodeId that doesn't exist, for example) are
 * silently no-ops. This is intentional — a server that sent a stale patch must not crash a
 * client that has already moved on.
 *
 * Traversal never descends past [maxDepth] levels: a target nested deeper than the budget is
 * treated as unresolvable (a no-op) and [telemetry] receives [SduiTelemetry.onNodeBudgetExceeded].
 * A patch tree is untrusted input, so bounding the recursion is what stops a pathologically deep
 * tree from overflowing the stack here just as [RenderNode] bounds it on the render path — both
 * paths share [MAX_UI_TREE_DEPTH] so they agree on which nodes are reachable.
 */
public fun Screen.apply(
    patch: TreePatch,
    maxDepth: Int = MAX_UI_TREE_DEPTH,
    telemetry: SduiTelemetry = NoopTelemetry,
): Screen {
    var root: UiNode = this.root
    for (op in patch.ops) {
        root = when (op) {
            is PatchOp.Replace -> root.replaceNode(op.nodeId, op.node, 0, maxDepth, telemetry)
            is PatchOp.Append -> root.appendInto(op.parentId, op.nodes, 0, maxDepth, telemetry)
            is PatchOp.Remove -> root.removeNodes(op.nodeIds.toSet(), 0, maxDepth, telemetry) ?: root
            // A patch op added by a newer server that this client cannot decode leaves the tree
            // unchanged rather than throwing.
            is PatchOp.Unknown -> root
        }
    }
    return if (root === this.root) this else copy(root = root)
}

private fun UiNode.replaceNode(
    target: NodeId,
    replacement: UiNode,
    depth: Int,
    maxDepth: Int,
    telemetry: SduiTelemetry,
): UiNode {
    if (id == target) return replacement
    if (this !is Container) return this
    if (depth >= maxDepth) {
        telemetry.onNodeBudgetExceeded(this::class.simpleName.orEmpty(), id, depth)
        return this
    }
    var changed = false
    val newChildren = children.map { child ->
        val replaced = child.replaceNode(target, replacement, depth + 1, maxDepth, telemetry)
        if (replaced !== child) changed = true
        replaced
    }
    return if (changed) withChildren(newChildren) else this
}

private fun UiNode.appendInto(
    parent: NodeId,
    nodes: List<UiNode>,
    depth: Int,
    maxDepth: Int,
    telemetry: SduiTelemetry,
): UiNode {
    if (this is Container && id == parent) return withChildren(children + nodes)
    if (this !is Container) return this
    if (depth >= maxDepth) {
        telemetry.onNodeBudgetExceeded(this::class.simpleName.orEmpty(), id, depth)
        return this
    }
    var changed = false
    val newChildren = children.map { child ->
        val replaced = child.appendInto(parent, nodes, depth + 1, maxDepth, telemetry)
        if (replaced !== child) changed = true
        replaced
    }
    return if (changed) withChildren(newChildren) else this
}

private fun UiNode.removeNodes(
    targets: Set<NodeId>,
    depth: Int,
    maxDepth: Int,
    telemetry: SduiTelemetry,
): UiNode? {
    if (id in targets) return null
    if (this !is Container) return this
    if (depth >= maxDepth) {
        telemetry.onNodeBudgetExceeded(this::class.simpleName.orEmpty(), id, depth)
        return this
    }
    var changed = false
    val newChildren = ArrayList<UiNode>(children.size)
    for (child in children) {
        val replaced = child.removeNodes(targets, depth + 1, maxDepth, telemetry)
        if (replaced !== child) changed = true
        if (replaced != null) newChildren += replaced
    }
    return if (changed) withChildren(newChildren) else this
}

/**
 * Copy a [Container] swapping in [children]. Adding a new Container subtype requires a branch
 * here — the `when` is exhaustive so the compiler flags omissions at build time.
 */
private fun Container.withChildren(children: List<UiNode>): Container = when (this) {
    is Column -> copy(children = children)
}

/** Alias used for consistency with the architecture document's wording. */
@Suppress("unused")
private fun allLeafTypesAreListedForExhaustiveness(node: UiNode) {
    // This function exists so when new Leaf types are added the compiler forces us to
    // think about whether they need a dedicated patch path (none do today — Leaves replace
    // wholesale via PatchOp.Replace), but the sealed `when` makes silent misses impossible.
    when (node) {
        is Column -> Unit
        is Text -> Unit
        is Button -> Unit
        is TextField -> Unit
        is Checkbox -> Unit
        is LazyList -> Unit
        is NavHost -> Unit
        is NativeSurface -> Unit
        is Image -> Unit
        is AsyncImage -> Unit
        is UnknownUiNode -> Unit
    }
}
