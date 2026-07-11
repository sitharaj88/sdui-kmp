package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Text entry bound two-way to [path]. Reads the current value from the state store and writes
 * every keystroke back. The renderer evaluates [validation] locally and reflects errors
 * inline; server-side validation happens on [Action.Submit].
 */
@Serializable
@SerialName("text_field")
public data class TextField(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val path: StatePath,
    public val placeholder: Value<String>? = null,
    public val keyboard: Keyboard = Keyboard.Text,
    public val secure: Boolean = false,
    public val validation: Validation? = null,
    public val a11y: A11y? = null,
) : Leaf

/**
 * Boolean toggle bound two-way to [path]. Missing or non-boolean state reads as false.
 */
@Serializable
@SerialName("checkbox")
public data class Checkbox(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val path: StatePath,
    public val label: Value<String>? = null,
    public val a11y: A11y? = null,
) : Leaf
