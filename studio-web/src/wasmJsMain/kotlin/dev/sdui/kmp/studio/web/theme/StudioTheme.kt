package dev.sdui.kmp.studio.web.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/**
 * The Studio's dark "pro tool" theme: dense dark panels, one accent color, crisp small
 * radii. Wraps [MaterialTheme] so every stock Material3 component picks the look up
 * automatically, and installs [LocalStudioColors] for the few semantics M3 lacks
 * (success / warning / code background — see [StudioColors]).
 *
 * The end-user screen preview must NOT inherit this theme — production clients render
 * tokens against stock Material3. `DevicePreviewFrame` nests its own stock scheme.
 */
@Composable
internal fun StudioTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStudioColors provides StudioDarkExtras) {
        MaterialTheme(
            colorScheme = StudioDarkColorScheme,
            typography = StudioTypography,
            shapes = StudioShapes,
        ) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = MaterialTheme.colorScheme.primary,
                    backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = SELECTION_BG_ALPHA),
                ),
                content = content,
            )
        }
    }
}

/** Accessor mirroring `MaterialTheme.colorScheme` ergonomics: `studioColors.success`. */
internal val studioColors: StudioColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStudioColors.current

/** Dark scheme; roles not listed keep `darkColorScheme` defaults. */
internal val StudioDarkColorScheme: ColorScheme = darkColorScheme(
    background = StudioPalette.Background,
    onBackground = StudioPalette.TextPrimary,
    surface = StudioPalette.Surface,
    onSurface = StudioPalette.TextPrimary,
    surfaceContainerLowest = StudioPalette.SurfaceLowest,
    surfaceContainerLow = StudioPalette.SurfaceLow,
    surfaceContainer = StudioPalette.SurfaceMid,
    surfaceContainerHigh = StudioPalette.SurfaceHigh,
    surfaceContainerHighest = StudioPalette.SurfaceHighest,
    surfaceVariant = StudioPalette.SurfaceMid,
    onSurfaceVariant = StudioPalette.TextMuted,
    outline = StudioPalette.BorderStrong,
    outlineVariant = StudioPalette.BorderHairline,
    primary = StudioPalette.Accent,
    onPrimary = StudioPalette.OnAccent,
    primaryContainer = StudioPalette.AccentContainer,
    onPrimaryContainer = StudioPalette.OnAccentContainer,
    secondary = StudioPalette.TextMuted,
    secondaryContainer = StudioPalette.SurfaceHigh,
    onSecondaryContainer = StudioPalette.TextPrimary,
    tertiary = StudioPalette.Warning,
    error = StudioPalette.Error,
    errorContainer = StudioPalette.ErrorContainer,
    onErrorContainer = StudioPalette.OnErrorContainer,
)

/** Small radii — the single cheapest "de-Materialize" lever for stock components. */
internal val StudioShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private const val SELECTION_BG_ALPHA = 0.30f
