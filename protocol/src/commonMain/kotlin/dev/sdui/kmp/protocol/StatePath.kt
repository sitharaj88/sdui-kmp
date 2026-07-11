package dev.sdui.kmp.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Dot-delimited path into the scoped state tree (e.g. `"user.profile.name"`).
 *
 * The only way to reference state. Raw strings are banned in widget fields so the linter and
 * renderer can reason about every binding.
 */
@JvmInline
@Serializable
public value class StatePath(public val value: String) {
    public fun child(segment: String): StatePath =
        if (value.isEmpty()) StatePath(segment) else StatePath("$value.$segment")

    public companion object {
        public val Root: StatePath = StatePath("")
    }
}
