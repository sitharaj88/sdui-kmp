package dev.sdui.kmp.widgetscore

import androidx.compose.runtime.Composable
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.resolve

/**
 * Composable bridge: reads [Value]`<String>` against the active store and triggers
 * recomposition when any bound path changes. Reading [store.snapshot.value] inside a
 * `@Composable` registers a subscription, so recomposition fires on any relevant write.
 */
@Composable
internal fun Value<String>.resolveString(): String {
    val store = LocalStateStore.current
    @Suppress("UNUSED_VARIABLE")
    val subscribe = store.snapshot.value
    return resolve(store)
}
