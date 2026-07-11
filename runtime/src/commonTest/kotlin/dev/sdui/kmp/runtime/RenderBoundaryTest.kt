package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.MAX_UI_TREE_DEPTH
import dev.sdui.kmp.protocol.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the render-path crash-safety contract that does not require a live composition. The
 * behavioral proof that a pathologically deep tree cannot overflow the stack lives in
 * [TreePatchEngineTest.pathologically_deep_tree_does_not_overflow_the_stack] (the pure, non-@Composable
 * traversal that shares [MAX_UI_TREE_DEPTH]). Here we pin the budget default and the telemetry hooks.
 */
class RenderBoundaryTest {

    private class RecordingTelemetry : SduiTelemetry {
        var budgetType: String? = null
        var budgetDepth: Int = -1
        var failureError: Throwable? = null
        var failureId: NodeId? = null

        override fun onNodeBudgetExceeded(type: String, id: NodeId, depth: Int) {
            budgetType = type
            budgetDepth = depth
        }

        override fun onRenderFailure(type: String, id: NodeId, error: Throwable) {
            failureId = id
            failureError = error
        }
    }

    @Test
    fun depth_budget_default_is_sane() {
        // Deep enough to nest real screens, shallow enough to never approach a JVM/native stack limit.
        assertTrue(MAX_UI_TREE_DEPTH in 16..4_096, "unexpected MAX_UI_TREE_DEPTH=$MAX_UI_TREE_DEPTH")
    }

    @Test
    fun budget_telemetry_hook_carries_the_offending_node_and_depth() {
        val telemetry = RecordingTelemetry()
        telemetry.onNodeBudgetExceeded("Column", NodeId("deep"), depth = MAX_UI_TREE_DEPTH)

        assertEquals("Column", telemetry.budgetType)
        assertEquals(MAX_UI_TREE_DEPTH, telemetry.budgetDepth)
    }

    @Test
    fun render_failure_hook_is_reportable_by_hosts() {
        // onRenderFailure is a host-invoked hook (Compose cannot catch composable exceptions); verify
        // an implementation can record it end-to-end.
        val telemetry = RecordingTelemetry()
        val boom = IllegalStateException("renderer blew up")
        telemetry.onRenderFailure("Text", NodeId("boom"), boom)

        assertEquals(NodeId("boom"), telemetry.failureId)
        assertEquals(boom, telemetry.failureError)
    }
}
