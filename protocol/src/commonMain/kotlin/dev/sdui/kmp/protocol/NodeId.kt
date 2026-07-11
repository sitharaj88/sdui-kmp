package dev.sdui.kmp.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Stable identity of a [UiNode] within a [Screen].
 *
 * Node ids are deterministic and survive across server restarts: the server DSL derives them from
 * the call-site hash, and patches apply against this id rather than a JSON pointer. Two screens
 * with the same DSL shape produce the same ids — this is essential for client-side state survival
 * across re-renders.
 */
@JvmInline
@Serializable
public value class NodeId(public val value: String)
