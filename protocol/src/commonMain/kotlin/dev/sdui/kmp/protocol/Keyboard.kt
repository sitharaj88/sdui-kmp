package dev.sdui.kmp.protocol

import kotlinx.serialization.Serializable

/** Virtual keyboard hint for [TextField]. Clients map to platform-appropriate IME types. */
@Serializable
public enum class Keyboard { Text, Email, Number, Phone, Url, Password }
