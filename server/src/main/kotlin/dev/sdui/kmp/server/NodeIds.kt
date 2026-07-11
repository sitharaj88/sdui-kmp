package dev.sdui.kmp.server

import dev.sdui.kmp.protocol.NodeId

/**
 * Derives a deterministic [NodeId] from the enclosing screen id, DSL call-site name, and a
 * per-scope ordinal.
 *
 * Two runs of the same server code produce identical ids — essential so client-side state
 * keyed by NodeId survives across re-renders. The id format is intentionally stable and
 * URL-safe (`screen/column[0]/text[2]`), which also reads well in logs.
 */
internal class NodeIdAllocator(private val screenId: String) {
    private val prefixCounts = mutableMapOf<String, Int>()

    fun next(kind: String, parentPath: String): NodeId {
        val path = if (parentPath.isEmpty()) "$screenId/$kind" else "$parentPath/$kind"
        val index = prefixCounts.getOrPut(path) { 0 }
        prefixCounts[path] = index + 1
        return NodeId("$path[$index]")
    }
}
