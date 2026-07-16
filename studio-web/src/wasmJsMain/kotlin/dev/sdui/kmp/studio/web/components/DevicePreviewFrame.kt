package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * The chrome adapts to [preset]: a Phone gets tall rounded corners and a centered speaker
 * notch; a Tablet a medium radius with a tiny camera dot; a Desktop a small radius and a slim
 * browser-style top bar with three window dots. All chrome is flat and hairline — no
 * skeuomorphic gloss — so it reads as a subtle wrapper, not a toy.
 *
 * @param preset the device form factor driving width, corner radius, and top-chrome style.
 * @param dark whether the nested device theme uses the stock dark scheme (editors flip this
 *   with the moon/sun toggle in the preview panel header; default is light, matching the
 *   sample end-user apps).
 */
@Composable
internal fun DevicePreviewFrame(
    preset: DevicePreset,
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Captured BEFORE nesting the stock theme so the frame border stays a Studio hairline.
    val frameBorder = MaterialTheme.colorScheme.outlineVariant
    val widthModifier = preset.contentWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        typography = Typography(),
        shapes = Shapes(),
    ) {
        Surface(
            modifier = modifier.then(widthModifier).fillMaxHeight(),
            shape = RoundedCornerShape(preset.cornerRadius),
            border = BorderStroke(1.dp, frameBorder),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                DeviceChrome(preset = preset)
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    content()
                }
            }
        }
    }
}

/**
 * The slim top chrome bar for [preset], drawn in the nested theme's own subtle color so it
 * reads correctly whether the device is showing the light or dark scheme. Closed off from the
 * content with a hairline divider.
 */
@Composable
private fun DeviceChrome(preset: DevicePreset) {
    val accent = MaterialTheme.colorScheme.outlineVariant
    when (preset) {
        DevicePreset.Phone -> CenteredChromeBar {
            Box(
                Modifier
                    .size(width = PHONE_NOTCH_WIDTH, height = PHONE_NOTCH_HEIGHT)
                    .clip(RoundedCornerShape(PHONE_NOTCH_HEIGHT))
                    .background(accent),
            )
        }
        DevicePreset.Tablet -> CenteredChromeBar {
            Box(Modifier.size(TABLET_CAMERA_SIZE).clip(CircleShape).background(accent))
        }
        DevicePreset.Desktop -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHROME_BAR_HEIGHT)
                .padding(horizontal = DESKTOP_DOTS_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DESKTOP_DOT_GAP),
        ) {
            repeat(DESKTOP_DOT_COUNT) {
                Box(Modifier.size(DESKTOP_DOT_SIZE).clip(CircleShape).background(accent))
            }
        }
    }
    HorizontalDivider(color = accent.copy(alpha = DIVIDER_ALPHA))
}

/** A full-width, fixed-height bar centering its single chrome ornament (notch / camera). */
@Composable
private fun CenteredChromeBar(ornament: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(CHROME_BAR_HEIGHT),
        contentAlignment = Alignment.Center,
    ) { ornament() }
}

private val CHROME_BAR_HEIGHT = 22.dp
private val PHONE_NOTCH_WIDTH = 44.dp
private val PHONE_NOTCH_HEIGHT = 5.dp
private val TABLET_CAMERA_SIZE = 5.dp
private val DESKTOP_DOT_SIZE = 7.dp
private val DESKTOP_DOT_GAP = 6.dp
private val DESKTOP_DOTS_INSET = 12.dp
private const val DESKTOP_DOT_COUNT = 3
private const val DIVIDER_ALPHA = 0.6f
