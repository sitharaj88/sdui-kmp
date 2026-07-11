package dev.sdui.kmp.studio.web.editor

import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class TreeMutatorTest {

    @Test
    fun startsWithInitialAndNoUndoRedo() {
        val initial = Column(id = NodeId("root"), since = SchemaVersion.V1)
        val mutator = TreeMutator(initial)
        assertSame(initial, mutator.current)
        assertFalse(mutator.canUndo)
        assertFalse(mutator.canRedo)
    }

    @Test
    fun replaceAdvancesUndoStack() {
        val a = Column(id = NodeId("a"), since = SchemaVersion.V1)
        val b = Column(id = NodeId("b"), since = SchemaVersion.V1)
        val mutator = TreeMutator(a)
        mutator.replace(b)
        assertSame(b, mutator.current)
        assertTrue(mutator.canUndo)
        assertFalse(mutator.canRedo)
    }

    @Test
    fun undoMovesBackAndEnablesRedo() {
        val a = Column(id = NodeId("a"), since = SchemaVersion.V1)
        val b = Column(id = NodeId("b"), since = SchemaVersion.V1)
        val mutator = TreeMutator(a)
        mutator.replace(b)
        mutator.undo()
        assertSame(a, mutator.current)
        assertTrue(mutator.canRedo)
        mutator.redo()
        assertSame(b, mutator.current)
    }

    @Test
    fun replaceAfterUndoClearsRedoTail() {
        val a = Column(id = NodeId("a"), since = SchemaVersion.V1)
        val b = Column(id = NodeId("b"), since = SchemaVersion.V1)
        val c = Column(id = NodeId("c"), since = SchemaVersion.V1)
        val mutator = TreeMutator(a)
        mutator.replace(b)
        mutator.undo()
        assertTrue(mutator.canRedo)
        mutator.replace(c)
        assertFalse(mutator.canRedo)
        assertSame(c, mutator.current)
    }

    @Test
    fun historyCapsAtFiftyEntries() {
        val mutator = TreeMutator(Text(id = NodeId("0"), content = Value.ofString("0")))
        repeat(60) { i ->
            mutator.replace(Text(id = NodeId("$i"), content = Value.ofString("$i")))
        }
        // 50-entry cap: undoing as far as possible should land on whatever the oldest still
        // in the buffer is, not the original initial node.
        var undoCount = 0
        while (mutator.canUndo) {
            mutator.undo()
            undoCount++
        }
        assertEquals(expected = MAX_HISTORY_FOR_TEST - 1, actual = undoCount)
    }

    @Test
    fun resetClearsHistory() {
        val a = Column(id = NodeId("a"), since = SchemaVersion.V1)
        val b = Column(id = NodeId("b"), since = SchemaVersion.V1)
        val mutator = TreeMutator(a)
        mutator.replace(b)
        val c = Column(id = NodeId("c"), since = SchemaVersion.V1)
        mutator.reset(c)
        assertSame(c, mutator.current)
        assertFalse(mutator.canUndo)
        assertFalse(mutator.canRedo)
    }

    private companion object {
        // Mirrors TreeMutator.MAX_HISTORY; kept in sync manually because the constant is private.
        const val MAX_HISTORY_FOR_TEST = 50
    }
}
