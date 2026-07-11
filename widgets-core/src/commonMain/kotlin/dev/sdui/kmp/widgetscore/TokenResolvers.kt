package dev.sdui.kmp.widgetscore

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

@Composable
@ReadOnlyComposable
internal fun ColorToken.toComposeColor(): Color = when (this) {
    ColorToken.Surface -> MaterialTheme.colorScheme.surface
    ColorToken.OnSurface -> MaterialTheme.colorScheme.onSurface
    ColorToken.Primary -> MaterialTheme.colorScheme.primary
    ColorToken.OnPrimary -> MaterialTheme.colorScheme.onPrimary
    ColorToken.Error -> MaterialTheme.colorScheme.error
    ColorToken.Warning -> MaterialTheme.colorScheme.tertiary
    ColorToken.Success -> MaterialTheme.colorScheme.primary
    ColorToken.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    // A color token added by a newer server that this client cannot decode falls back to a
    // neutral, always-legible foreground rather than throwing.
    is ColorToken.Unknown -> MaterialTheme.colorScheme.onSurface
}

@Composable
@ReadOnlyComposable
internal fun TextStyleToken.toTextStyle(): TextStyle = when (this) {
    TextStyleToken.Display -> MaterialTheme.typography.displayMedium
    TextStyleToken.Heading -> MaterialTheme.typography.headlineMedium
    TextStyleToken.Title -> MaterialTheme.typography.titleLarge
    TextStyleToken.Body -> MaterialTheme.typography.bodyLarge
    TextStyleToken.BodySmall -> MaterialTheme.typography.bodySmall
    TextStyleToken.Caption -> MaterialTheme.typography.bodySmall
    TextStyleToken.Label -> MaterialTheme.typography.labelMedium
    TextStyleToken.Error -> MaterialTheme.typography.bodyMedium
}

internal fun Spacing.toDp(): Dp = when (this) {
    Spacing.None -> 0.dp
    Spacing.Xs -> 4.dp
    Spacing.Sm -> 8.dp
    Spacing.Md -> 16.dp
    Spacing.Lg -> 24.dp
    Spacing.Xl -> 32.dp
    Spacing.Xxl -> 48.dp
}

internal data class ResolvedInsets(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

internal fun EdgeInsets.resolve(): ResolvedInsets =
    ResolvedInsets(start = start.toDp(), top = top.toDp(), end = end.toDp(), bottom = bottom.toDp())
