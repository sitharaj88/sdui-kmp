package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Device" frame for rendering the end-user screen preview.
 *
 * The production renderer resolves semantic tokens (`ColorToken`, `TextStyleToken`) against
 * the AMBIENT `MaterialTheme` — and the Studio's own theme is dark, dense, and accent-blue,
 * which is NOT what production clients ship. This frame nests a **stock** Material3 theme
 * (default color scheme, typography, and shapes) so the preview looks like the real app,
 * sitting inside a rounded, hairline-bordered device surface on the Studio's dark canvas —
 * the Figma-style "canvas inside a tool" read.
 *
 * @param dark whether the nested device theme uses the stock dark scheme (editors flip this
 *   with the moon/sun toggle in the preview panel header; default is light, matching the
 *   sample end-user apps).
 */
@Composable
internal fun DevicePreviewFrame(
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Captured BEFORE nesting the stock theme so the frame border stays a Studio hairline.
    val frameBorder = MaterialTheme.colorScheme.outlineVariant
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        typography = Typography(),
        shapes = Shapes(),
    ) {
        Surface(
            modifier = modifier.widthIn(max = DEVICE_MAX_WIDTH).fillMaxHeight(),
            shape = RoundedCornerShape(DEVICE_CORNER_RADIUS),
            border = BorderStroke(1.dp, frameBorder),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

private val DEVICE_MAX_WIDTH = 420.dp
private val DEVICE_CORNER_RADIUS = 12.dp
