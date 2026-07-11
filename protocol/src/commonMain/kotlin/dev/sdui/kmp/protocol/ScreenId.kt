package dev.sdui.kmp.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Stable identity of a screen, used for navigation, caching, and telemetry. */
@JvmInline
@Serializable
public value class ScreenId(public val value: String)
