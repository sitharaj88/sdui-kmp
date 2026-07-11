package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Runs [content] with a [StateScope.Node] child store seeded from [initial] and keyed by
 * [itemKey]. Lazy-list renderers use this per visible row so bindings like
 * `Value.Bind(StatePath("liked"))` inside the item template resolve against per-item state
 * without colliding across rows.
 *
 * Writes inside [content] affect the child scope only; reads fall through to screen / global
 * state on miss, matching the scope semantics defined in [StateStore].
 */
@Composable
public fun ItemScope(
    itemKey: String,
    initial: JsonObject,
    content: @Composable () -> Unit,
) {
    val parent = LocalStateStore.current
    val childStore = remember(itemKey, parent, initial) {
        parent.child(scope = StateScope.Node, initial = initial.toStatePathMap())
    }
    CompositionLocalProvider(LocalStateStore provides childStore) {
        content()
    }
}

private fun JsonObject.toStatePathMap(): Map<StatePath, JsonElement> =
    mapKeys { (k, _) -> StatePath(k) }
