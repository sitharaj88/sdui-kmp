package dev.sdui.kmp.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Monotonic protocol schema version. Each new widget or field introduction bumps this.
 *
 * Clients carry the schema version they were built against. Nodes older than the client are
 * always renderable; nodes newer fall back via [UiNode.fallback].
 */
@JvmInline
@Serializable
public value class SchemaVersion(public val value: Int) : Comparable<SchemaVersion> {
    override fun compareTo(other: SchemaVersion): Int = value.compareTo(other.value)

    public companion object {
        public val V1: SchemaVersion = SchemaVersion(1)
    }
}
