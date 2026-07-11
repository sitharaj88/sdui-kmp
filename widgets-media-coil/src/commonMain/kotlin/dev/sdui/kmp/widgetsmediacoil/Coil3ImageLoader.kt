package dev.sdui.kmp.widgetsmediacoil

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.sdui.kmp.protocol.ContentScale
import dev.sdui.kmp.widgetsmedia.ImageLoader
import androidx.compose.ui.layout.ContentScale as ComposeContentScale
import coil3.ImageLoader as CoilImageLoader
import coil3.compose.AsyncImage as CoilAsyncImage

/**
 * [ImageLoader] backed by [Coil 3](https://coil-kt.github.io/coil/), the Kotlin Multiplatform
 * iteration of Coil. Wraps a host-supplied Coil [CoilImageLoader] so apps control the cache,
 * memory budget, interceptors, and network stack while widgets stay transport-agnostic.
 *
 * Build via [create] for sensible defaults, or pass a pre-configured Coil loader directly.
 *
 * Wire it from the host:
 * ```kotlin
 * CompositionLocalProvider(LocalImageLoader provides Coil3ImageLoader.create(platformContext)) {
 *     SduiHost(...)
 * }
 * ```
 */
public class Coil3ImageLoader(
    private val coilLoader: CoilImageLoader,
) : ImageLoader {

    @Composable
    override fun Image(
        source: String,
        contentDescription: String?,
        contentScale: ContentScale,
        modifier: Modifier,
    ) {
        // "Image" in the protocol is for single-fetch / bundled-asset URIs (file://, content://,
        // android.resource://, classpath assets). Coil resolves all of these synchronously enough
        // for first-frame display; we use the simple AsyncImage with no placeholder/error subtree.
        val request = ImageRequest.Builder(LocalPlatformContext.current)
            .data(source)
            .build()
        CoilAsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            imageLoader = coilLoader,
            contentScale = contentScale.toComposeContentScale(),
        )
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
        val request = ImageRequest.Builder(LocalPlatformContext.current)
            .data(url)
            .build()
        // SubcomposeAsyncImage lets us render arbitrary UiNode subtrees during loading and on
        // error — exactly what the protocol's `placeholder`/`error` fields ask for. The Coil
        // loader still handles the fetch, decode, cache, and crossfade.
        SubcomposeAsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            imageLoader = coilLoader,
            contentScale = contentScale.toComposeContentScale(),
        ) {
            // Coil 3's painter.state is a StateFlow — collect it inside composition so we
            // recompose on every transition (Loading -> Success/Error).
            val state by painter.state.collectAsState()
            when (state) {
                is AsyncImagePainter.State.Loading -> {
                    if (placeholder != null) {
                        placeholder(Modifier)
                    } else {
                        // Reserve the layout box so the image slot doesn't collapse during fetch.
                        Box(Modifier)
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    if (error != null) {
                        error(Modifier)
                    } else {
                        // Swallow the failure visually rather than crashing — the framework's
                        // contract is "client never crashes on degraded data". Empty slot.
                        Box(Modifier)
                    }
                }
                is AsyncImagePainter.State.Success,
                is AsyncImagePainter.State.Empty -> {
                    SubcomposeAsyncImageContent()
                }
            }
        }
    }

    public companion object {
        /**
         * Build a [Coil3ImageLoader] with a default Coil [CoilImageLoader]: Ktor 3 network
         * fetcher (so HTTP(S) URLs work on every KMP target) plus crossfade. Hosts that need a
         * custom cache, OkHttp, or interceptors should construct their own [CoilImageLoader] and
         * pass it to the [Coil3ImageLoader] constructor.
         */
        public fun create(context: PlatformContext): Coil3ImageLoader {
            val coil = CoilImageLoader.Builder(context)
                .components { add(KtorNetworkFetcherFactory()) }
                .crossfade(true)
                .build()
            return Coil3ImageLoader(coil)
        }
    }
}

/** Maps the protocol [ContentScale] enum onto Compose's `ContentScale` family. */
private fun ContentScale.toComposeContentScale(): ComposeContentScale = when (this) {
    ContentScale.Fit -> ComposeContentScale.Fit
    ContentScale.Fill -> ComposeContentScale.FillBounds
    ContentScale.Crop -> ComposeContentScale.Crop
    ContentScale.Inside -> ComposeContentScale.Inside
    ContentScale.None -> ComposeContentScale.None
}
