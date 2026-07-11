package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath

/**
 * Telemetry hooks the runtime invokes. Host apps wire these to their analytics pipeline; the
 * framework ships [NoopTelemetry] as the inert default.
 */
public interface SduiTelemetry {
    public fun onScreenRendered(id: ScreenId, version: SchemaVersion, durationMs: Long) {}
    public fun onNodeRendered(type: String, version: SchemaVersion) {}
    public fun onUnknownNode(type: String, trace: List<NodeId>) {}
    public fun onActionDispatched(action: Action, durationMs: Long) {}
    public fun onBindingError(path: StatePath, expected: String, got: String) {}

    /**
     * A node was skipped because the tree exceeded [dev.sdui.kmp.protocol.MAX_UI_TREE_DEPTH] (or the
     * host-configured ceiling). The node at [id] of the given [type] sits at [depth]; its subtree is
     * not rendered or patched. Fires instead of a `StackOverflowError`.
     */
    public fun onNodeBudgetExceeded(type: String, id: NodeId, depth: Int) {}

    /**
     * A registered renderer threw [error] while composing the node at [id] of the given [type].
     *
     * Compose does not permit catching exceptions across a `@Composable` invocation, so the runtime
     * cannot fire this automatically from `RenderNode`. It exists for hosts that install a
     * platform-level composition error handler (e.g. an Android `Thread.UncaughtExceptionHandler`
     * or a top-level wrapper) and want renderer failures to reach the same telemetry pipeline as
     * the framework's own events. Well-behaved widget renderers should not throw on well-formed
     * input.
     */
    public fun onRenderFailure(type: String, id: NodeId, error: Throwable) {}
}

/** Telemetry that discards every event. Safe for tests and hosts that haven't wired up analytics yet. */
public data object NoopTelemetry : SduiTelemetry
