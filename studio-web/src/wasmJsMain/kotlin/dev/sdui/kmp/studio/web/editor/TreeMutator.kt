package dev.sdui.kmp.studio.web.editor

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.sdui.kmp.protocol.UiNode

/**
 * Path through a [UiNode] tree by container-child index. Empty path = root.
 *
 * For example, `[0, 2]` denotes "the third child of the first child of the root container".
 * Paths that do not resolve through a [dev.sdui.kmp.protocol.Container] at every step are
 * invalid and the [TreeOps] helpers return `null`.
 */
public typealias TreePath = List<Int>

/**
 * In-memory undo / redo–backed mutator over a [UiNode] root.
 *
 * The mutator is intentionally minimal: callers compute the next tree (using [TreeOps] helpers
 * or by hand) and call [replace]. The mutator manages history; it does not know about insertion,
 * deletion, or per-field updates. This keeps the type independent of the concrete protocol
 * sub-class set, matching the framework's additive-evolution rule.
 *
 * History is bounded by [MAX_HISTORY] entries; older entries fall off the front. Calling
 * [replace] after [undo] truncates the redo tail, the conventional VS Code / IDE behaviour.
 */
public class TreeMutator(initial: UiNode) {
    private val state: MutableState<UiNode> = mutableStateOf(initial)
    private val history: ArrayDeque<UiNode> = ArrayDeque<UiNode>().apply { addLast(initial) }
    private var historyIndex: Int = 0

    /** The current root node. Reads of this property compose-observe the underlying state. */
    public val current: UiNode get() = state.value

    /** True iff [undo] would move backwards. */
    public val canUndo: Boolean get() = historyIndex > 0

    /** True iff [redo] would move forwards. */
    public val canRedo: Boolean get() = historyIndex < history.size - 1

    /**
     * Reset the mutator to a fresh [newRoot] and clear all history.
     *
     * Used when the caller decodes a brand-new screen body (for example after switching
     * between draft and published in the JSON tab).
     */
    public fun reset(newRoot: UiNode) {
        history.clear()
        history.addLast(newRoot)
        historyIndex = 0
        state.value = newRoot
    }

    /**
     * Replace the current root with [newRoot] and push it onto the undo stack.
     *
     * Any redo entries beyond the current index are discarded. The history is capped at
     * [MAX_HISTORY] entries; once full, the oldest entry is removed (so the index does not
     * advance past the cap).
     */
    public fun replace(newRoot: UiNode) {
        while (history.size - 1 > historyIndex) {
            history.removeLast()
        }
        history.addLast(newRoot)
        if (history.size > MAX_HISTORY) {
            history.removeFirst()
        } else {
            historyIndex++
        }
        state.value = newRoot
    }

    /** Move one step backwards in the history if possible. No-op when [canUndo] is false. */
    public fun undo() {
        if (!canUndo) return
        historyIndex--
        state.value = history[historyIndex]
    }

    /** Move one step forwards in the history if possible. No-op when [canRedo] is false. */
    public fun redo() {
        if (!canRedo) return
        historyIndex++
        state.value = history[historyIndex]
    }

    private companion object {
        private const val MAX_HISTORY: Int = 50
    }
}
