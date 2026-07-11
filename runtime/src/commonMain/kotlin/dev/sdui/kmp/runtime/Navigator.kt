package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.sdui.kmp.protocol.Destination
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * Navigation surface the runtime dispatches [dev.sdui.kmp.protocol.Action.Navigate] through.
 *
 * Protocol v0 exercises only [Destination.ScreenDest] and [Destination.Back]; additional
 * destinations ([Destination.Modal], [Destination.TabSwitch], [Destination.PopToRoot]) land
 * in later milestones and existing implementations should treat them as no-ops until then.
 */
public interface Navigator {
    /** Observable top-of-stack route. `null` when the stack is empty. */
    public val current: State<String?>

    public fun navigate(destination: Destination, replace: Boolean = false)
    public fun push(route: String)
    public fun replace(route: String)
    public fun pop(count: Int = 1)
    public fun popToRoot()
    public fun switchTab(tabId: String)
}

/**
 * Default in-memory stack navigator. Enough for M2 samples; richer hosts (deep linking,
 * shared-element transitions) provide their own implementations behind the same interface.
 *
 * `switchTab` is a no-op until M4 introduces `NavHost`; calling it does not throw.
 */
public class StackNavigator(initial: String? = null) : Navigator {
    private val stack = mutableStateOf<PersistentList<String>>(
        if (initial != null) persistentListOf(initial) else persistentListOf(),
    )
    override val current: State<String?> = derivedStateOf { stack.value.lastOrNull() }

    override fun navigate(destination: Destination, replace: Boolean) {
        when (destination) {
            is Destination.ScreenDest -> if (replace) replace(destination.route) else push(destination.route)
            is Destination.Back -> pop(destination.count)
            is Destination.Modal -> push(destination.route) // plain push until NavHost renderer gates modal chrome
            is Destination.TabSwitch -> switchTab(destination.tabId)
            Destination.PopToRoot -> popToRoot()
            // A destination added by a newer server that this client cannot decode: no-op so
            // navigation never crashes on a forward-compatible target.
            is Destination.Unknown -> Unit
        }
    }

    override fun push(route: String) {
        stack.value = stack.value.add(route)
    }

    override fun replace(route: String) {
        val cur = stack.value
        stack.value = if (cur.isEmpty()) cur.add(route) else cur.set(cur.lastIndex, route)
    }

    override fun pop(count: Int) {
        if (count <= 0) return
        val cur = stack.value
        val target = (cur.size - count).coerceAtLeast(0)
        stack.value = cur.take(target).toPersistentList()
    }

    override fun popToRoot() {
        val cur = stack.value
        stack.value = if (cur.isEmpty()) cur else persistentListOf(cur.first())
    }

    override fun switchTab(tabId: String) {
        // No-op until NavHost lands in M4.
    }

    /** Snapshot of the current stack. For tests and debug only. */
    public fun snapshot(): List<String> = stack.value
}

/** Navigator that discards every call. Useful for previews and unit tests. */
public data object NoOpNavigator : Navigator {
    private val none = mutableStateOf<String?>(null)
    override val current: State<String?> get() = none
    override fun navigate(destination: Destination, replace: Boolean) {}
    override fun push(route: String) {}
    override fun replace(route: String) {}
    override fun pop(count: Int) {}
    override fun popToRoot() {}
    override fun switchTab(tabId: String) {}
}

/**
 * Composable factory that remembers a [StackNavigator] across recompositions.
 */
@Composable
public fun rememberNavigator(initial: String? = null): StackNavigator =
    remember(initial) { StackNavigator(initial) }
