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
