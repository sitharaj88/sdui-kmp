package dev.sdui.kmp.widgetsforms

import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.StateStore
import dev.sdui.kmp.runtime.resolve

private val EmailPattern = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

/**
 * Evaluates [Validation] against the live [input] string. Returns `null` when valid, or a
 * caller-friendly error message otherwise. Messages come from the protocol when present;
 * fallbacks use stable English strings — localization is a host concern once M9 wires it up.
 */
public fun Validation.check(input: String, store: StateStore): String? {
    return when (this) {
        is Validation.Required -> if (input.isEmpty()) resolveMessage(message, store, default = "Required") else null
        is Validation.MinLength -> if (input.length < length)
            resolveMessage(message, store, default = "Minimum $length characters")
        else null
        is Validation.MaxLength -> if (input.length > length)
            resolveMessage(message, store, default = "Maximum $length characters")
        else null
        is Validation.Email -> if (input.isNotEmpty() && !EmailPattern.matches(input))
            resolveMessage(message, store, default = "Invalid email")
        else null
        is Validation.All -> validations.firstNotNullOfOrNull { it.check(input, store) }
        // A validation rule added by a newer server that this client cannot decode passes (returns
        // null): an unknown rule must never block the user.
        is Validation.Unknown -> null
    }
}

private fun resolveMessage(message: Value<String>?, store: StateStore, default: String): String =
    message?.resolve(store) ?: default
