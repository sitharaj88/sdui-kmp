package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.Value
import kotlinx.serialization.json.JsonPrimitive

/**
 * The visual editor's own token→visual mapping.
 *
 * `:widgets-core`'s resolvers are `internal` (deliberately — the editor must not couple to the
 * production render path, ADR-0019), so the canvas re-derives the same mapping against the
 * ambient `MaterialTheme`. The canvas sits inside a stock device theme, so these resolve to
 * production-like values, not the Studio's dark chrome.
 */

/** Maps a semantic color token (or null) to the ambient scheme; mirrors widgets-core. */
@Composable
@ReadOnlyComposable
internal fun ColorToken?.toCanvasColor(): Color = when (this) {
    ColorToken.Surface -> MaterialTheme.colorScheme.surface
    ColorToken.OnSurface -> MaterialTheme.colorScheme.onSurface
    ColorToken.Primary -> MaterialTheme.colorScheme.primary
    ColorToken.OnPrimary -> MaterialTheme.colorScheme.onPrimary
    ColorToken.Error -> MaterialTheme.colorScheme.error
    ColorToken.Warning -> MaterialTheme.colorScheme.tertiary
    ColorToken.Success -> MaterialTheme.colorScheme.primary
    ColorToken.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

/** Maps a text style token to the ambient typography; mirrors widgets-core. */
@Composable
@ReadOnlyComposable
internal fun TextStyleToken.toCanvasStyle(): TextStyle = when (this) {
    TextStyleToken.Display -> MaterialTheme.typography.displaySmall
    TextStyleToken.Heading -> MaterialTheme.typography.headlineSmall
    TextStyleToken.Title -> MaterialTheme.typography.titleMedium
    TextStyleToken.Body -> MaterialTheme.typography.bodyMedium
    TextStyleToken.BodySmall -> MaterialTheme.typography.bodySmall
    TextStyleToken.Caption -> MaterialTheme.typography.labelSmall
    TextStyleToken.Label -> MaterialTheme.typography.labelMedium
    TextStyleToken.Error -> MaterialTheme.typography.bodyMedium
}

/** Spacing token to Dp — the canonical scale from `:widgets-core`'s TokenResolvers. */
internal fun Spacing.toCanvasDp(): Dp = when (this) {
    Spacing.None -> 0.dp
    Spacing.Xs -> SPACING_XS
    Spacing.Sm -> SPACING_SM
    Spacing.Md -> SPACING_MD
    Spacing.Lg -> SPACING_LG
    Spacing.Xl -> SPACING_XL
    Spacing.Xxl -> SPACING_XXL
}

/** EdgeInsets to per-edge PaddingValues via [toCanvasDp]. */
internal fun EdgeInsets.toCanvasPadding(): PaddingValues = PaddingValues(
    start = start.toCanvasDp(),
    top = top.toCanvasDp(),
    end = end.toCanvasDp(),
    bottom = bottom.toCanvasDp(),
)

/** How a `Value<String>` presents in the inert canvas. */
internal sealed interface CanvasText {
    /** A literal string — rendered as-is. */
    data class Literal(val text: String) : CanvasText

    /** A state binding — rendered as a muted `{path}` placeholder. */
    data class Bound(val label: String) : CanvasText

    /** A template — rendered as its raw pattern, muted. */
    data class Templated(val pattern: String) : CanvasText

    /** Null or unknown value. */
    data object Absent : CanvasText
}

/** Classifies [value] for inert display; binds and templates render as placeholders. */
internal fun previewText(value: Value<String>?): CanvasText = when (value) {
    null -> CanvasText.Absent
    is Value.Literal<*> -> {
        val primitive = value.value as? JsonPrimitive
        CanvasText.Literal(primitive?.content ?: value.value.toString())
    }
    is Value.Bind<*> -> CanvasText.Bound("{${value.path.value}}")
    is Value.Template -> CanvasText.Templated(value.pattern)
    is Value.Unknown -> CanvasText.Absent
}

/** The display string for [this], regardless of variant. Empty for [CanvasText.Absent]. */
internal fun CanvasText.displayString(): String = when (this) {
    is CanvasText.Literal -> text
    is CanvasText.Bound -> label
    is CanvasText.Templated -> pattern
    CanvasText.Absent -> ""
}

/** True when the value should render in the muted "dynamic placeholder" treatment. */
internal fun CanvasText.isPlaceholder(): Boolean = this !is CanvasText.Literal

private val SPACING_XS = 4.dp
private val SPACING_SM = 8.dp
private val SPACING_MD = 16.dp
private val SPACING_LG = 24.dp
private val SPACING_XL = 32.dp
private val SPACING_XXL = 48.dp
