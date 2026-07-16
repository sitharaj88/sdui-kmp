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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single, reusable "device" chrome both Studio surfaces frame a screen inside — the JSON
 * tab's live preview ([DevicePreviewFrame]) and the visual editor's WYSIWYG canvas.
 *
 * The production renderer resolves semantic tokens (`ColorToken`, `TextStyleToken`) against the
 * AMBIENT `MaterialTheme`, so this scaffold nests a **stock** Material3 theme (default scheme,
 * typography, and shapes) — the framed content then looks like a real client rather than the
 * Studio's dark, accent-blue tool chrome. The frame is a rounded, hairline-bordered surface
 * carrying [preset]-specific top chrome: a Phone speaker notch, a Tablet camera dot, or a
 * Desktop browser bar with three window dots. All chrome is flat and hairline — no skeuomorphic
 * gloss — so it reads as a subtle wrapper, not a toy.
 *
 * Any [androidx.compose.runtime.CompositionLocal] provided by the caller ABOVE this scaffold
 * stays visible inside [content] (nesting a `MaterialTheme` only overrides Material's own
 * locals) — the editor canvas relies on this to keep its `LocalChromeColors` readable by the
 * inert node renderers composed inside the nested stock theme.
 *
 * @param preset the device form factor driving width, corner radius, and top-chrome style.
 * @param dark whether the nested device theme uses the stock dark scheme (the preview pane
 *   flips this with its moon/sun toggle; the editor canvas pins it light so selection outlines
 *   always read against the content).
 * @param fillHeight when `true` the frame fills the available height and its content area
 *   scrolls within it (the preview pane); when `false` the frame wraps its content's height
 *   like a card floating on the editor's backdrop.
 * @param contentPadding inner padding applied around [content] inside the scroll area.
 */
@Composable
internal fun DeviceFrameScaffold(
    preset: DevicePreset,
    dark: Boolean,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = true,
    contentPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    // Captured BEFORE nesting the stock theme so the frame border stays a Studio hairline.
    val frameBorder = MaterialTheme.colorScheme.outlineVariant
    val widthModifier = preset.contentWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()
    val heightModifier = if (fillHeight) Modifier.fillMaxHeight() else Modifier
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        typography = Typography(),
        shapes = Shapes(),
    ) {
        Surface(
            modifier = modifier.then(widthModifier).then(heightModifier),
            shape = RoundedCornerShape(preset.cornerRadius),
            border = BorderStroke(1.dp, frameBorder),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                DeviceChrome(preset = preset)
                val scrollModifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                Box(scrollModifier.verticalScroll(rememberScrollState()).padding(contentPadding)) {
                    content()
                }
            }
        }
    }
}

/**
 * The slim top chrome bar for [preset], drawn in the nested theme's own subtle color so it
 * reads correctly whether the device shows the light or dark scheme. Closed off from the
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
