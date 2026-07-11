package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A boolean expression over the scoped state tree. Evaluated by the renderer, not by a
 * scripting engine.
 *
 * Deliberately small: no arithmetic, no string ops, no regex. If the server needs richer
 * logic, the server runs the logic; the wire protocol stays renderable by a dumb client.
 */
@Serializable
public sealed interface Predicate {
    /** True when the value at [path] is structurally equal to [value]. */
    @Serializable
    @SerialName("eq")
    public data class Eq(public val path: StatePath, public val value: JsonElement) : Predicate

    /** True when [inner] is false. */
    @Serializable
    @SerialName("not")
    public data class Not(public val inner: Predicate) : Predicate

    /** True when [path] resolves to null, empty string, empty array, or empty object. */
    @Serializable
    @SerialName("empty")
    public data class IsEmpty(public val path: StatePath) : Predicate

    /** Conjunction. True when every predicate in [predicates] is true. */
    @Serializable
    @SerialName("all")
    public data class All(public val predicates: List<Predicate>) : Predicate

    /** Disjunction. True when any predicate in [predicates] is true. */
    @Serializable
    @SerialName("any")
    public data class Any(public val predicates: List<Predicate>) : Predicate

    /**
     * Inert sentinel decoded when the `type` discriminator names a [Predicate] this client does
     * not recognize. Evaluators treat it as `false` (neutral) rather than throwing, so a newer
     * predicate kind can never blank the screen on an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : Predicate
}
