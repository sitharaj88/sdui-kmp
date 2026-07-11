package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Visual treatment variants for [Button]. */
@Serializable
public enum class ButtonStyle { Primary, Secondary, Tertiary, Destructive }

/**
 * Vertical stack of [children]. Maps to Compose `Column`.
 */
@Serializable
@SerialName("column")
public data class Column(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    override val children: List<UiNode> = emptyList(),
    public val spacing: Spacing = Spacing.None,
    public val padding: EdgeInsets = EdgeInsets.Zero,
    public val a11y: A11y? = null,
) : Container

/**
 * Static or state-bound text. [content] is a [Value] so it can be bound to state without a
 * separate widget for "dynamic text".
 */
@Serializable
@SerialName("text")
public data class Text(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val content: Value<String>,
    public val style: TextStyleToken = TextStyleToken.Body,
    public val color: ColorToken? = null,
    public val a11y: A11y? = null,
) : Leaf

/**
 * Tappable widget whose [action] is dispatched through the host's `ActionHandler`.
 */
@Serializable
@SerialName("button")
public data class Button(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val label: Value<String>,
    public val action: Action,
    public val style: ButtonStyle = ButtonStyle.Primary,
    public val a11y: A11y? = null,
) : Leaf
