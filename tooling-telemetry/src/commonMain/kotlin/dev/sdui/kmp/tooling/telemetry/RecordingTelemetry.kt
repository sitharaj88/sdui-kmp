package dev.sdui.kmp.tooling.telemetry

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.runtime.SduiTelemetry

/**
 * [SduiTelemetry] test double that records every call into per-event lists.
 *
 * Used by golden/integration tests that want to assert the runtime emitted the right events
 * — e.g. "this patch fired exactly one `onUnknownNode`" — without wiring a real analytics
 * pipeline. Not thread-safe; tests drive it from a single coroutine context.
 */
public class RecordingTelemetry : SduiTelemetry {
    public data class ScreenRenderedEvent(
        public val id: ScreenId,
        public val version: SchemaVersion,
        public val durationMs: Long,
    )

    public data class NodeRenderedEvent(
        public val type: String,
        public val version: SchemaVersion,
    )

    public data class UnknownNodeEvent(
        public val type: String,
        public val trace: List<NodeId>,
    )

    public data class ActionDispatchedEvent(
        public val action: Action,
        public val durationMs: Long,
    )

    public data class BindingErrorEvent(
        public val path: StatePath,
        public val expected: String,
        public val got: String,
    )

    private val _screenRendered: MutableList<ScreenRenderedEvent> = mutableListOf()
    private val _nodeRendered: MutableList<NodeRenderedEvent> = mutableListOf()
    private val _unknownNode: MutableList<UnknownNodeEvent> = mutableListOf()
    private val _actionDispatched: MutableList<ActionDispatchedEvent> = mutableListOf()
    private val _bindingError: MutableList<BindingErrorEvent> = mutableListOf()

    public val screenRendered: List<ScreenRenderedEvent> get() = _screenRendered.toList()
    public val nodeRendered: List<NodeRenderedEvent> get() = _nodeRendered.toList()
    public val unknownNode: List<UnknownNodeEvent> get() = _unknownNode.toList()
    public val actionDispatched: List<ActionDispatchedEvent> get() = _actionDispatched.toList()
    public val bindingError: List<BindingErrorEvent> get() = _bindingError.toList()

    override fun onScreenRendered(id: ScreenId, version: SchemaVersion, durationMs: Long) {
        _screenRendered += ScreenRenderedEvent(id, version, durationMs)
    }

    override fun onNodeRendered(type: String, version: SchemaVersion) {
        _nodeRendered += NodeRenderedEvent(type, version)
    }

    override fun onUnknownNode(type: String, trace: List<NodeId>) {
        _unknownNode += UnknownNodeEvent(type, trace)
    }

    override fun onActionDispatched(action: Action, durationMs: Long) {
        _actionDispatched += ActionDispatchedEvent(action, durationMs)
    }

    override fun onBindingError(path: StatePath, expected: String, got: String) {
        _bindingError += BindingErrorEvent(path, expected, got)
    }

    /** Clears every recorded event. Useful between test cases sharing one instance. */
    public fun reset() {
        _screenRendered.clear()
        _nodeRendered.clear()
        _unknownNode.clear()
        _actionDispatched.clear()
        _bindingError.clear()
    }
}
