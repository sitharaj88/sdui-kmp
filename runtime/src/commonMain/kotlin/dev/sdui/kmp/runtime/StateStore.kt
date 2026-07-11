package dev.sdui.kmp.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateScope
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.json.JsonElement

/**
 * Reactive key-value store keyed by [StatePath], scoped as a parent/child tree.
 *
 * Reads resolve locally first, then fall through to [parent]. Writes land only in the local
 * scope — a child never mutates its parent's map. [snapshot] presents the effective merged
 * view (child keys override parent keys) so Compose consumers can simply read it as a map.
 *
 * Create children via [child] when entering a new scope (screen-open, lazy-list item, modal).
 * At M3, [SduiHost] uses a single root store; nested scopes start being exercised with
 * [dev.sdui.kmp.protocol.Container]-level state in M4.
 */
@Stable
public class StateStore(
    initial: Map<StatePath, JsonElement> = emptyMap(),
    private val parent: StateStore? = null,
    public val scope: StateScope = StateScope.Global,
) {
    private val local = mutableStateOf<PersistentMap<StatePath, JsonElement>>(initial.toPersistentMap())

    /**
     * Observable merged view (parent + local, local wins). Recomposition fires whenever the
     * local or any ancestor's local map changes.
     */
    public val snapshot: State<PersistentMap<StatePath, JsonElement>> =
        if (parent == null) local
        else derivedStateOf { parent.snapshot.value.putAll(local.value) }

    /** Reads with fall-through. Null when no scope in the chain defines [path]. */
    public fun read(path: StatePath): JsonElement? = snapshot.value[path]

    /** Writes [value] to the **local** scope only. Does not affect ancestors. */
    public fun update(path: StatePath, value: JsonElement) {
        local.value = local.value.put(path, value)
    }

    /** Atomic multi-key local write. Triggers a single recomposition. */
    public fun patch(updates: Map<StatePath, JsonElement>) {
        local.value = local.value.putAll(updates)
    }

    /** Delete a key from the **local** scope. If a parent scope also has this key, it becomes visible again. */
    public fun remove(path: StatePath) {
        local.value = local.value.remove(path)
    }

    /** Entries defined directly in this scope. Useful for tests and debug dumps. */
    public fun localSnapshot(): Map<StatePath, JsonElement> = local.value

    /**
     * Construct a child store. [initial] seeds the child scope; reads for anything missing
     * fall through to this receiver.
     */
    public fun child(scope: StateScope, initial: Map<StatePath, JsonElement> = emptyMap()): StateStore =
        StateStore(initial = initial, parent = this, scope = scope)

    public companion object {
        public val Empty: StateStore = StateStore()
        public fun of(vararg pairs: Pair<StatePath, JsonElement>): StateStore =
            StateStore(persistentMapOf(*pairs))
    }
}
