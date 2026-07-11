package dev.sdui.kmp.protocol

import kotlinx.serialization.Serializable

/**
 * Accessibility metadata accepted by every widget.
 *
 * Placed on the protocol base before v1 ships because retrofitting a11y into a live protocol
 * is a major version bump. Every renderer is responsible for mapping these into the native
 * accessibility APIs of its platform.
 */
@Serializable
public data class A11y(
    public val label: Value<String>? = null,
    public val hint: Value<String>? = null,
    public val role: A11yRole? = null,
    public val liveRegion: LiveRegion = LiveRegion.Off,
    public val isHidden: Boolean = false,
    public val headingLevel: Int? = null,
)

/** Semantic role of a widget, used by screen readers. */
@Serializable
public enum class A11yRole {
    Button, Link, Image, Header, List, ListItem, Checkbox, Radio, Switch, Slider, TextField,
}

/** Whether state changes under this node should be announced to the screen reader. */
@Serializable
public enum class LiveRegion { Off, Polite, Assertive }
