package dev.sdui.kmp.studio.web.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * "Device" frame for rendering the end-user screen preview in the JSON tab's preview pane.
 *
 * A thin wrapper over the shared [DeviceFrameScaffold] that fills the pane height so the preview
 * behaves like a live device viewport. The scaffold nests a **stock** Material3 theme so tokens
 * resolve to production-like colors rather than the Studio's dark tool chrome, and draws the
 * [preset]-specific bezel chrome (notch / camera dot / browser dots).
 *
 * @param preset the device form factor driving width, corner radius, and top-chrome style.
 * @param dark whether the nested device theme uses the stock dark scheme (editors flip this with
 *   the moon/sun toggle in the preview panel header; default is light, matching the sample
 *   end-user apps).
 */
@Composable
internal fun DevicePreviewFrame(
    preset: DevicePreset,
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    DeviceFrameScaffold(
        preset = preset,
        dark = dark,
        modifier = modifier,
        fillHeight = true,
        content = content,
    )
}
