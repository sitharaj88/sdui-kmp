package dev.sdui.kmp.widgetsnav

import dev.sdui.kmp.runtime.WidgetRegistry

/**
 * Registers the nav-host renderer into [builder].
 *
 * Host apps call this from their registry setup so they do not need to know the concrete
 * renderer type; mirrors the `WidgetsCore.register` / `WidgetsForms.register` pattern.
 *
 * The single registered renderer ([NavHostRenderer]) dispatches by [dev.sdui.kmp.protocol.NavKind]
 * to a tab, bottom-sheet, or stack implementation at composition time.
 */
public object WidgetsNav {
    public fun register(builder: WidgetRegistry.Builder): WidgetRegistry.Builder =
        builder.register(NavHostRenderer)
}
