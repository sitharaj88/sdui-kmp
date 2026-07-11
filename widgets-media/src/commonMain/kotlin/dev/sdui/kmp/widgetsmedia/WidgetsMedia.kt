package dev.sdui.kmp.widgetsmedia

import dev.sdui.kmp.runtime.WidgetRegistry

/** Registers `Image` and `AsyncImage` into [builder]. */
public object WidgetsMedia {
    public fun register(builder: WidgetRegistry.Builder): WidgetRegistry.Builder =
        builder
            .register(ImageRenderer)
            .register(AsyncImageRenderer)
}
