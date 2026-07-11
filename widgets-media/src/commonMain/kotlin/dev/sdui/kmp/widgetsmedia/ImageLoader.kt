package dev.sdui.kmp.widgetsmedia

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.ContentScale

/**
 * Host-supplied image loader. The runtime ships [PlaceholderImageLoader] as the inert default;
 * host apps wire a real loader (Coil 3 multiplatform, Kamel, or a bespoke implementation)
 * behind this SAM so widget renderers stay transport-agnostic.
 *
 * The two methods cover the `Image` / `AsyncImage` split: [Image] is synchronous (bundled
 * asset, content URI, file path); [AsyncImage] carries a URL plus optional placeholder/error
 * sub-trees rendered while fetching or on failure.
 */
public interface ImageLoader {
    @Composable
    public fun Image(
        source: String,
        contentDescription: String?,
        contentScale: ContentScale,
        modifier: Modifier,
    )

    @Composable
    public fun AsyncImage(
        url: String,
        contentDescription: String?,
        contentScale: ContentScale,
        placeholder: (@Composable (Modifier) -> Unit)?,
        error: (@Composable (Modifier) -> Unit)?,
        modifier: Modifier,
    )
}

/**
 * Default loader that renders a labeled placeholder box instead of actually fetching.
 * Keeps the protocol testable cross-platform without pulling in a network image dep; real
 * apps override via [LocalImageLoader].
 */
public data object PlaceholderImageLoader : ImageLoader {
    @Composable
    override fun Image(
        source: String,
        contentDescription: String?,
        contentScale: ContentScale,
        modifier: Modifier,
    ) {
        PlaceholderBox(label = source, contentDescription = contentDescription, modifier = modifier)
    }

    @Composable
    override fun AsyncImage(
        url: String,
        contentDescription: String?,
        contentScale: ContentScale,
        placeholder: (@Composable (Modifier) -> Unit)?,
        error: (@Composable (Modifier) -> Unit)?,
        modifier: Modifier,
    ) {
        // No async fetch to await; keep behavior predictable — render the placeholder sub-tree
        // if the server supplied one, else the labeled box. Real loaders will render the
        // placeholder during the fetch and swap to the image on success.
        if (placeholder != null) {
            placeholder(modifier)
        } else {
            PlaceholderBox(label = url, contentDescription = contentDescription, modifier = modifier)
        }
    }
}

/**
 * Renders the design-time placeholder box. The visible `[<label>]` is the *internal*
 * stand-in that helps developers see which image slot they're inspecting; the *accessible*
 * name is whatever the server supplied via `Image.contentDescription` /
 * `AsyncImage.contentDescription`. When the server supplied no description we mark the box
 * `invisibleToUser()` (WCAG 1.1.1) — a decorative placeholder must not pollute the
 * announcement order with the raw URL.
 */
@Composable
@Suppress("FunctionNaming") // Compose composables are PascalCase by convention.
private fun PlaceholderBox(label: String, contentDescription: String?, modifier: Modifier) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier.semantics { invisibleToUser() }
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "[$label]",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxSize(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Active [ImageLoader]. Defaults to [PlaceholderImageLoader]. */
public val LocalImageLoader: ProvidableCompositionLocal<ImageLoader> =
    staticCompositionLocalOf { PlaceholderImageLoader }
