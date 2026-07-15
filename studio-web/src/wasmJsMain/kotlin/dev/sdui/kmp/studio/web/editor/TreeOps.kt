package dev.sdui.kmp.studio.web.editor

import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Container
import dev.sdui.kmp.protocol.UiNode

/**
 * Pure-recursive helpers operating on a [UiNode] tree by [TreePath].
 *
 * These helpers know about every concrete [Container] type in `:protocol` so they can rebuild
 * the parent with a new children list. The current protocol version exposes only [Column];
 * additional containers should be added here when introduced.
 *
 * Returning `null` signals an invalid path (out-of-range index, descent into a non-container,
 * or a non-container target where one was required).
 */

/** Returns the node at [path] in `this`, or `null` if [path] does not resolve. */
internal fun UiNode.childAt(path: TreePath): UiNode? {
    if (path.isEmpty()) return this
    val container = this as? Container ?: return null
    val head = path.first()
    return container.children.getOrNull(head)?.childAt(path.drop(1))
}

/**
 * Returns a copy of this tree with the node at [path] replaced by [replacement].
 *
 * Empty path returns [replacement] directly. Invalid paths return `null`.
 */
@Suppress("ReturnCount")
internal fun UiNode.replacingAt(path: TreePath, replacement: UiNode): UiNode? {
    if (path.isEmpty()) return replacement
    val container = this as? Container ?: return null
    val head = path.first()
    val existing = container.children.getOrNull(head) ?: return null
    val updatedChild = existing.replacingAt(path.drop(1), replacement) ?: return null
    return container.withChildAt(head, updatedChild)
}

/**
 * Returns a copy of this tree with [child] appended to the children of the container at [path].
 *
 * The node at [path] must be a [Container]. Returns `null` if the path is invalid or resolves to
 * a non-container.
 */
@Suppress("ReturnCount")
internal fun UiNode.insertingChild(path: TreePath, child: UiNode): UiNode? {
    val container = this as? Container ?: return null
    if (path.isEmpty()) return container.withChildren(container.children + child)
    val head = path.first()
    val existing = container.children.getOrNull(head) ?: return null
    val updatedChild = existing.insertingChild(path.drop(1), child) ?: return null
    return container.withChildAt(head, updatedChild)
}

/**
 * Returns a copy of this tree with the node at [path] removed.
 *
 * Empty path returns `null` (cannot remove the root). Removing the only child of a container is
 * legal and yields an empty container.
 */
@Suppress("ReturnCount")
internal fun UiNode.removingAt(path: TreePath): UiNode? {
    if (path.isEmpty()) return null
    val container = this as? Container ?: return null
    val head = path.first()
    val existing = container.children.getOrNull(head) ?: return null
    if (path.size == 1) {
        return container.withChildren(container.children.toMutableList().apply { removeAt(head) })
    }
    val updatedChild = existing.removingAt(path.drop(1)) ?: return null
    return container.withChildAt(head, updatedChild)
}

/**
 * Returns a copy of this tree with [child] inserted at [index] into the children of the
 * [Container] at [path].
 *
 * Returns `null` when [path] does not resolve, resolves to a non-container, or [index] is
 * outside `0..children.size`. `index == children.size` appends, so
 * `insertingChild(p, c) == insertingChildAt(p, children.size, c)`.
 */
@Suppress("ReturnCount")
internal fun UiNode.insertingChildAt(path: TreePath, index: Int, child: UiNode): UiNode? {
    val container = this as? Container ?: return null
    if (path.isEmpty()) {
        if (index !in 0..container.children.size) return null
        return container.withChildren(
            container.children.toMutableList().apply { add(index, child) },
        )
    }
    val head = path.first()
    val existing = container.children.getOrNull(head) ?: return null
    val updatedChild = existing.insertingChildAt(path.drop(1), index, child) ?: return null
    return container.withChildAt(head, updatedChild)
}

/** True when `this` path equals [ancestor] or lies strictly inside its subtree. */
internal fun TreePath.isSelfOrDescendantOf(ancestor: TreePath): Boolean =
    size >= ancestor.size && take(ancestor.size) == ancestor

/**
 * Returns a copy of this tree with the node at [from] removed and re-inserted into the
 * [Container] at [toContainer] at [index].
 *
 * [index] is interpreted against the container's children as the caller sees them BEFORE the
 * removal; when the removal itself shifts the destination (moving within the same parent, or
 * into a container that sits after the source among its siblings) the index and path are
 * adjusted internally — see [adjustedDestination].
 *
 * Returns `null` when:
 *  * [from] is empty (cannot move the root),
 *  * [from] does not resolve,
 *  * [toContainer] is [from] or a descendant of [from] (would create a cycle),
 *  * [toContainer] does not resolve to a [Container] in the post-removal tree,
 *  * the adjusted index is outside `0..children.size`.
 *
 * A move that lands the node back in its original position returns a tree structurally equal
 * to the receiver (not `null`) — callers use equality to skip no-op history entries.
 */
@Suppress("ReturnCount")
internal fun UiNode.movingNode(from: TreePath, toContainer: TreePath, index: Int): UiNode? {
    if (from.isEmpty()) return null
    if (toContainer.isSelfOrDescendantOf(from)) return null
    val node = childAt(from) ?: return null
    val (adjustedPath, adjustedIndex) = adjustedDestination(from, toContainer, index)
    val removed = removingAt(from) ?: return null
    return removed.insertingChildAt(adjustedPath, adjustedIndex, node)
}

/**
 * Translates a pre-removal drop destination into post-removal coordinates for [movingNode].
 *
 * Two shifts can apply once the node at [from] is removed:
 *  * the destination container's own path loses one index where it passes the removed
 *    sibling (`toContainer` runs through `from`'s parent at a later index), and
 *  * a destination *inside the same parent* after the removed slot loses one index.
 *
 * Exposed (rather than private) so drag-drop UI code can compute the node's post-move
 * selection path with exactly the same rule the op uses.
 */
internal fun adjustedDestination(from: TreePath, toContainer: TreePath, index: Int): Pair<TreePath, Int> {
    val parent = from.dropLast(1)
    val removedIndex = from.last()
    val adjustedPath = if (
        toContainer.size > parent.size &&
        toContainer.take(parent.size) == parent &&
        toContainer[parent.size] > removedIndex
    ) {
        toContainer.toMutableList().apply { this[parent.size] = this[parent.size] - 1 }
    } else {
        toContainer
    }
    val adjustedIndex = if (toContainer == parent && index > removedIndex) index - 1 else index
    return adjustedPath to adjustedIndex
}

/** Replace exactly one child of `this` at [index] with [child] and return the rebuilt container. */
private fun Container.withChildAt(index: Int, child: UiNode): Container =
    withChildren(children.toMutableList().apply { this[index] = child })

/**
 * Rebuilds a [Container] with a fresh children list. Add a branch here whenever `:protocol`
 * introduces a new [Container] subtype.
 */
internal fun Container.withChildren(newChildren: List<UiNode>): Container = when (this) {
    is Column -> copy(children = newChildren)
    else -> this
}
