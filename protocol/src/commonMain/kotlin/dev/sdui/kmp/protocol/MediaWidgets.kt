package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How an image fits into its layout box. Matches Compose's `ContentScale` set we care about. */
@Serializable
public enum class ContentScale { Fit, Fill, Crop, Inside, None }

/**
 * A synchronous image — bundled asset, content URI, or file path. The renderer interprets the
 * [source] string; by default [dev.sdui.kmp.widgetsmedia.PlaceholderImageLoader] renders a
 * labeled box, and host apps plug in a real loader (Coil, Kamel, ...) via [ImageLoader].
 */
@Serializable
@SerialName("image")
public data class Image(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val source: Value<String>,
    public val contentDescription: Value<String>? = null,
    public val contentScale: ContentScale = ContentScale.Fit,
    public val a11y: A11y? = null,
) : Leaf

/**
 * A remote image with dedicated placeholder / error sub-trees rendered while the host's
 * [ImageLoader] is fetching or has failed.
 */
@Serializable
@SerialName("async_image")
public data class AsyncImage(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val url: Value<String>,
    public val contentDescription: Value<String>? = null,
    public val contentScale: ContentScale = ContentScale.Fit,
    public val placeholder: UiNode? = null,
    public val error: UiNode? = null,
    public val a11y: A11y? = null,
) : Leaf
