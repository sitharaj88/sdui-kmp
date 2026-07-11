package dev.sdui.kmp.widgetsforms

import dev.sdui.kmp.runtime.WidgetRegistry

/** Registers `TextField` and `Checkbox` into [builder]. */
public object WidgetsForms {
    public fun register(builder: WidgetRegistry.Builder): WidgetRegistry.Builder =
        builder
            .register(TextFieldRenderer)
            .register(CheckboxRenderer)
}
