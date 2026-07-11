package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client-side validation attached to a form field.
 *
 * Deliberately small — mirrors [Predicate]'s restraint. Clients ship a canonical interpretation
 * of [Email]; server-authored regex is explicitly not supported (no arbitrary expression
 * languages on the wire, per VISION).
 */
@Serializable
public sealed interface Validation {
    /** Value must be present and non-empty. */
    @Serializable @SerialName("required")
    public data class Required(public val message: Value<String>? = null) : Validation

    @Serializable @SerialName("min_length")
    public data class MinLength(public val length: Int, public val message: Value<String>? = null) : Validation

    @Serializable @SerialName("max_length")
    public data class MaxLength(public val length: Int, public val message: Value<String>? = null) : Validation

    @Serializable @SerialName("email")
    public data class Email(public val message: Value<String>? = null) : Validation

    /** Every inner validation must pass. */
    @Serializable @SerialName("all")
    public data class All(public val validations: List<Validation>) : Validation

    /**
     * Inert sentinel decoded when the `type` discriminator names a [Validation] this client does
     * not recognize. Evaluators treat it as "passes" (neutral — an unknown rule must not block
     * the user) rather than throwing, so a newer validation kind can never break an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : Validation
}
