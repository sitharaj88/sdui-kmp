package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.UiNode

/**
 * The single entry point through which any [UiNode] becomes pixels.
 *
 * Looks the node up in the registry; if no renderer is registered (or if the client version
 * is outside the renderer's handled range), the telemetry hook fires and control falls to
 * the node's [UiNode.fallback] (recursively). If there is no fallback, nothing renders — the
 * function must not throw. This is invariant #3 from [VISION.md](VISION.md).
 *
 * The guard that keeps that invariant honest against hostile input is the **depth budget**: every
 * descent (into a child or a fallback) increments [LocalRenderDepth]; once it reaches
 * [LocalMaxRenderDepth] the node is skipped with [SduiTelemetry.onNodeBudgetExceeded] rather than
 * recursing further, so a pathologically deep server tree cannot overflow the stack.
 *
 * Note on renderer exceptions: Compose does **not** permit `try`/`catch` around a `@Composable`
 * invocation, so a registered renderer that throws mid-composition cannot be caught here — that is
 * a client-side renderer bug, distinct from the hostile-input cases this function defends
 * (unknown node types → fallback; excessive depth → skip). Widget renderers must therefore not
 * throw on well-formed input; hosts that install a platform-level error handler can report caught
 * failures through [SduiTelemetry.onRenderFailure].
 */
@Composable
public fun RenderNode(node: UiNode, modifier: Modifier = Modifier) {
    val registry = LocalRegistry.current
    val telemetry = LocalTelemetry.current
    val depth = LocalRenderDepth.current
    if (depth >= LocalMaxRenderDepth.current) {
        // Budget exhausted: rendering nothing is the deliberate, safe choice — descending into the
        // fallback would consume yet another frame. The whole subtree is dropped, never thrown.
        telemetry.onNodeBudgetExceeded(node::class.simpleName.orEmpty(), node.id, depth)
        return
    }
    val renderer = registry.rendererFor(node)
    if (renderer != null) {
        telemetry.onNodeRendered(node::class.simpleName.orEmpty(), node.since)
        CompositionLocalProvider(LocalRenderDepth provides depth + 1) {
            renderer.Render(node, modifier)
        }
    } else {
        telemetry.onUnknownNode(node::class.simpleName.orEmpty(), listOf(node.id))
        val fb = node.fallback
        if (fb != null) {
            CompositionLocalProvider(LocalRenderDepth provides depth + 1) {
                RenderNode(fb, modifier)
            }
        }
    }
}
