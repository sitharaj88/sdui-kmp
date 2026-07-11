package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A possibly state-bound value of type [T].
 *
 * Every widget field that could be dynamic must be typed `Value<T>`, never raw `T`. This is
 * how the linter can reason about every binding in the protocol — and why there are no raw
 * strings inside widget fields for dynamic data.
 *
 * At the wire level, literal values are stored as [JsonElement]; [T] is a compile-time phantom
 * that keeps the server DSL and renderer honest. Construct typed literals with [ofString],
 * [ofInt], [ofBoolean], or [ofJson] rather than wrapping [JsonPrimitive] directly.
 *
 * Template interpolation is a separate variant reserved for M3.
 */
@Serializable
public sealed interface Value<out T> {
    /**
     * A constant value emitted by the server at build time. On-wire the value is always a
     * [JsonElement]; callers at the Kotlin level see it through the typed helpers.
     */
    @Serializable
    @SerialName("literal")
    public data class Literal<T>(public val value: JsonElement) : Value<T>

    /** A reference to a path in the scoped state tree. Resolved at render time. */
    @Serializable
    @SerialName("bind")
    public data class Bind<T>(public val path: StatePath) : Value<T>

    /**
     * String interpolation. [pattern] contains `{key}` placeholders; each `{key}` is replaced
     * at render time with the state at [bindings]`[key]`. Unknown placeholders are left
     * untouched. Template is always a `Value<String>` — typed literals go through [Literal].
     */
    @Serializable
    @SerialName("template")
    public data class Template(
        public val pattern: String,
        public val bindings: Map<String, StatePath> = emptyMap(),
    ) : Value<String>

    /**
     * Inert sentinel decoded when the `type` discriminator names a [Value] variant this client
     * does not recognize. Resolvers treat it as an absent/empty value rather than throwing, so a
     * newer binding kind can never blank the screen on an older client.
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it. Fixed to `Value<Nothing>` so it is assignable wherever any `Value<T>` is
     * expected (the interface is covariant in [T]).
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : Value<Nothing>

    public companion object {
        public fun ofString(value: String): Value<String> = Literal(JsonPrimitive(value))
        public fun ofInt(value: Int): Value<Int> = Literal(JsonPrimitive(value))
        public fun ofBoolean(value: Boolean): Value<Boolean> = Literal(JsonPrimitive(value))
        public fun ofJson(element: JsonElement): Value<JsonElement> = Literal(element)
        public fun template(pattern: String, bindings: Map<String, StatePath> = emptyMap()): Value<String> =
            Template(pattern, bindings)
    }
}
