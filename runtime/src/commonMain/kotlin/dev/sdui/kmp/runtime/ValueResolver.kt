package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Value
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private val TemplatePlaceholder = Regex("\\{([^{}]+)\\}")

/**
 * Resolves a [Value]`<String>` to a plain string against [store].
 *
 * - [Value.Literal]: the stored [JsonElement]'s primitive content (empty string for null).
 * - [Value.Bind]: reads the state path and coerces to string.
 * - [Value.Template]: single-pass placeholder substitution; unknown `{key}` placeholders
 *   are left in place so their absence is visible rather than silently empty.
 */
public fun Value<String>.resolve(store: StateStore): String = when (this) {
    is Value.Literal<String> -> (value as? JsonPrimitive)?.contentOrEmpty() ?: ""
    is Value.Bind<String> -> (store.read(path) as? JsonPrimitive)?.contentOrEmpty() ?: ""
    is Value.Template -> TemplatePlaceholder.replace(pattern) { match ->
        val key = match.groupValues[1]
        val path = bindings[key] ?: return@replace match.value
        (store.read(path) as? JsonPrimitive)?.contentOrEmpty() ?: ""
    }
    // A value kind added by a newer server that this client cannot decode resolves to the empty
    // string rather than throwing.
    is Value.Unknown -> ""
}

private fun JsonPrimitive.contentOrEmpty(): String = if (this is JsonNull) "" else content
