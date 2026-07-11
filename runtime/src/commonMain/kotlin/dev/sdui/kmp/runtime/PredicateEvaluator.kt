package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Predicate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Evaluates a [Predicate] against [store]. Deliberately total — every predicate type in the
 * sealed hierarchy is handled, so adding a new predicate is a compile error here until
 * covered.
 */
public fun Predicate.evaluate(store: StateStore): Boolean = when (this) {
    is Predicate.Eq -> store.read(path) == value
    is Predicate.Not -> !inner.evaluate(store)
    is Predicate.IsEmpty -> when (val v = store.read(path)) {
        null, JsonNull -> true
        is JsonPrimitive -> v.content.isEmpty()
        is JsonArray -> v.isEmpty()
        is JsonObject -> v.isEmpty()
    }
    is Predicate.All -> predicates.all { it.evaluate(store) }
    is Predicate.Any -> predicates.any { it.evaluate(store) }
    // A predicate added by a newer server that this client cannot decode evaluates to false —
    // a neutral result that never throws.
    is Predicate.Unknown -> false
}
