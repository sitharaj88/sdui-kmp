package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Semantic color slots. Never raw hex. Rebranding is a client release, not a protocol change.
 */
@Serializable
public sealed interface ColorToken {
    @Serializable @SerialName("surface") public data object Surface : ColorToken
    @Serializable @SerialName("on_surface") public data object OnSurface : ColorToken
    @Serializable @SerialName("primary") public data object Primary : ColorToken
    @Serializable @SerialName("on_primary") public data object OnPrimary : ColorToken
    @Serializable @SerialName("error") public data object Error : ColorToken
    @Serializable @SerialName("warning") public data object Warning : ColorToken
    @Serializable @SerialName("success") public data object Success : ColorToken
    @Serializable @SerialName("muted") public data object Muted : ColorToken

    /**
     * Inert sentinel decoded when the `type` discriminator names a [ColorToken] this client does
     * not recognize. Resolvers map it to a neutral foreground color rather than throwing, so a
     * newer semantic color slot can never blank the screen on an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : ColorToken
}

/** Scale of spacing values; clients map each rung to concrete `dp`. */
@Serializable
public enum class Spacing { None, Xs, Sm, Md, Lg, Xl, Xxl }

/** Semantic typography roles. Clients own the actual font, size, and tracking. */
@Serializable
public enum class TextStyleToken {
    Display, Heading, Title, Body, BodySmall, Caption, Label, Error,
}

/** Corner radius scale. */
@Serializable
public enum class RadiusToken { None, Sm, Md, Lg, Full }

/** Drop-shadow / surface elevation scale. */
@Serializable
public enum class ElevationToken { None, Sm, Md, Lg }

/** Semantic icon slot. `Named` is a platform-portable slug like `"add"`, `"chevron_right"`. */
@Serializable
public sealed interface IconToken {
    @Serializable @SerialName("named")
    public data class Named(public val name: String) : IconToken

    /**
     * Inert sentinel decoded when the `type` discriminator names an [IconToken] this client does
     * not recognize. Renderers treat it as "no icon" rather than throwing, so a newer icon token
     * kind can never blank the screen on an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : IconToken
}

/**
 * Symmetric padding around a widget, in [Spacing] units.
 *
 * Use [symmetric] or [all] for the common cases; the named constructor is available for
 * directional padding when needed.
 */
@Serializable
public data class EdgeInsets(
    public val top: Spacing = Spacing.None,
    public val start: Spacing = Spacing.None,
    public val end: Spacing = Spacing.None,
    public val bottom: Spacing = Spacing.None,
) {
    public companion object {
        public val Zero: EdgeInsets = EdgeInsets()
        public fun all(spacing: Spacing): EdgeInsets =
            EdgeInsets(top = spacing, start = spacing, end = spacing, bottom = spacing)
        public fun symmetric(horizontal: Spacing = Spacing.None, vertical: Spacing = Spacing.None): EdgeInsets =
            EdgeInsets(top = vertical, start = horizontal, end = horizontal, bottom = vertical)
    }
}
