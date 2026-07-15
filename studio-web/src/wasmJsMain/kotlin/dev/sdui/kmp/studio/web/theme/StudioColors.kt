package dev.sdui.kmp.studio.web.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Raw hex palette for the Studio's dark "pro tool" theme. These constants are consumed by
 * [StudioDarkColorScheme] and [StudioDarkExtras]; views never reference them directly —
 * they read `MaterialTheme.colorScheme.*` or [StudioTheme.colors] instead, so a future
 * light theme only has to swap the scheme, not touch call sites.
 */
internal object StudioPalette {
    /** App canvas — the deepest level, behind everything. */
    val Background = Color(0xFF0F1115)

    /** Default component surface: top bar, nav rail. */
    val Surface = Color(0xFF14171D)

    /** Recessed wells / insets, darker than the canvas. */
    val SurfaceLowest = Color(0xFF0C0E12)

    /** Panels and cards. */
    val SurfaceLow = Color(0xFF171B22)

    /** Inputs and chip backgrounds. */
    val SurfaceMid = Color(0xFF1B2029)

    /** Hover states, dropdown menus, dialogs. */
    val SurfaceHigh = Color(0xFF222834)

    /** Selected segments, pressed states. */
    val SurfaceHighest = Color(0xFF29303E)

    /** Primary text. */
    val TextPrimary = Color(0xFFE6E9EF)

    /** Secondary / muted text. */
    val TextMuted = Color(0xFF9BA3B1)

    /** Strong border — focused inputs, emphasis. */
    val BorderStrong = Color(0xFF343B49)

    /** The 1px hairline used on every panel seam. */
    val BorderHairline = Color(0xFF262C37)

    /** The single accent. */
    val Accent = Color(0xFF4C8DFF)

    /** Text/icons sitting on the accent. */
    val OnAccent = Color(0xFF0A0E16)

    /** Accent-tinted container (selection indicator). */
    val AccentContainer = Color(0xFF16345F)

    /** Text on [AccentContainer]. */
    val OnAccentContainer = Color(0xFFAECBFF)

    /** Error red. */
    val Error = Color(0xFFE5534B)

    /** Error banner background. */
    val ErrorContainer = Color(0xFF42201D)

    /** Text on [ErrorContainer]. */
    val OnErrorContainer = Color(0xFFFFB3AC)

    /** Success green (M3 has no success role). */
    val Success = Color(0xFF3DBE8B)

    /** Text on success-tinted backgrounds. */
    val OnSuccessTint = Color(0xFF0D2A1F)

    /** Warning amber (doubles as M3 `tertiary`). */
    val Warning = Color(0xFFD8A03E)

    /** JSON editor background — darker than any panel so code reads as a distinct well. */
    val CodeBg = Color(0xFF101319)
}

/**
 * Semantic colors Material3's `ColorScheme` genuinely lacks. Kept to five fields on purpose:
 * everything expressible as an M3 role stays an M3 role so stock components pick it up.
 *
 * Read via [StudioTheme.colors], provided by [StudioTheme].
 */
@Immutable
internal class StudioColors(
    val success: Color,
    val onSuccessTint: Color,
    val warning: Color,
    val codeBg: Color,
    val hoverOverlay: Color,
)

/** Dark-theme instance of the extended colors. */
internal val StudioDarkExtras: StudioColors = StudioColors(
    success = StudioPalette.Success,
    onSuccessTint = StudioPalette.OnSuccessTint,
    warning = StudioPalette.Warning,
    codeBg = StudioPalette.CodeBg,
    hoverOverlay = Color.White.copy(alpha = HOVER_OVERLAY_ALPHA),
)

/** CompositionLocal carrying [StudioColors]; installed by [StudioTheme]. */
internal val LocalStudioColors = staticCompositionLocalOf<StudioColors> {
    error("LocalStudioColors accessed outside StudioTheme")
}

private const val HOVER_OVERLAY_ALPHA = 0.04f
