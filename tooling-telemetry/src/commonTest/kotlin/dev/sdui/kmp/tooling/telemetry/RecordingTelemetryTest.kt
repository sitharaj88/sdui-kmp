package dev.sdui.kmp.tooling.telemetry

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingTelemetryTest {

    @Test
    fun records_every_event_type_in_order() {
        val telemetry = RecordingTelemetry()
        telemetry.onScreenRendered(ScreenId("home"), SchemaVersion.V1, 42L)
        telemetry.onNodeRendered("text", SchemaVersion.V1)
        telemetry.onUnknownNode("mystery", listOf(NodeId("n1")))
        telemetry.onActionDispatched(Action.Navigate(Destination.Back()), 3L)
        telemetry.onBindingError(StatePath("x"), expected = "string", got = "number")

        assertEquals(1, telemetry.screenRendered.size)
        assertEquals(ScreenId("home"), telemetry.screenRendered[0].id)
        assertEquals(1, telemetry.nodeRendered.size)
        assertEquals("text", telemetry.nodeRendered[0].type)
        assertEquals(1, telemetry.unknownNode.size)
        assertEquals("mystery", telemetry.unknownNode[0].type)
        assertEquals(1, telemetry.actionDispatched.size)
        assertTrue(telemetry.actionDispatched[0].action is Action.Navigate)
        assertEquals(1, telemetry.bindingError.size)
    }

    @Test
    fun reset_clears_every_list() {
        val telemetry = RecordingTelemetry()
        telemetry.onNodeRendered("text", SchemaVersion.V1)
        telemetry.reset()
        assertEquals(0, telemetry.nodeRendered.size)
    }

    @Test
    fun returned_lists_are_snapshots_not_live() {
        val telemetry = RecordingTelemetry()
        telemetry.onNodeRendered("a", SchemaVersion.V1)
        val snapshot = telemetry.nodeRendered
        telemetry.onNodeRendered("b", SchemaVersion.V1)
        // The previously-captured snapshot should not change after later events.
        assertEquals(1, snapshot.size)
        assertEquals(2, telemetry.nodeRendered.size)
    }
}
