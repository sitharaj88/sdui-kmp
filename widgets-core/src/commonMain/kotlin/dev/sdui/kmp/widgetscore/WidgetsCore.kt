package dev.sdui.kmp.widgetscore

import dev.sdui.kmp.runtime.WidgetRegistry

/**
 * Registers the three protocol-v0 widgets — `Column`, `Text`, `Button` — into [builder].
 *
 * Host apps call this from their registry setup so they never have to know the concrete
 * renderer types. Additional widget modules (`widgets-forms`, `widgets-media`) expose
 * analogous top-level `register` entry points.
 */
public object WidgetsCore {
    public fun register(builder: WidgetRegistry.Builder): WidgetRegistry.Builder =
        builder
            .register(ColumnRenderer)
            .register(TextRenderer)
            .register(ButtonRenderer)
            .register(LazyListRenderer)
}
