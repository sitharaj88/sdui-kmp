package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Predicate
import dev.sdui.kmp.protocol.StatePath
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PredicateEvaluatorTest {
    private fun storeWith(vararg pairs: Pair<StatePath, kotlinx.serialization.json.JsonElement>) =
        StateStore(pairs.toMap())

    @Test
    fun eq_matches_structural_equality() {
        val store = storeWith(StatePath("name") to JsonPrimitive("alice"))
        assertTrue(Predicate.Eq(StatePath("name"), JsonPrimitive("alice")).evaluate(store))
        assertFalse(Predicate.Eq(StatePath("name"), JsonPrimitive("bob")).evaluate(store))
    }

    @Test
    fun not_negates_inner() {
        val store = storeWith(StatePath("flag") to JsonPrimitive(true))
        assertFalse(Predicate.Not(Predicate.Eq(StatePath("flag"), JsonPrimitive(true))).evaluate(store))
    }

    @Test
    fun is_empty_handles_null_jsonnull_empty_string_empty_array_empty_object() {
        val store = StateStore(
            mapOf(
                StatePath("jsonnull") to JsonNull,
                StatePath("emptyStr") to JsonPrimitive(""),
                StatePath("emptyArr") to JsonArray(emptyList()),
                StatePath("emptyObj") to JsonObject(emptyMap()),
                StatePath("full") to JsonPrimitive("hi"),
            ),
        )
        assertTrue(Predicate.IsEmpty(StatePath("missing")).evaluate(store))
        assertTrue(Predicate.IsEmpty(StatePath("jsonnull")).evaluate(store))
        assertTrue(Predicate.IsEmpty(StatePath("emptyStr")).evaluate(store))
        assertTrue(Predicate.IsEmpty(StatePath("emptyArr")).evaluate(store))
        assertTrue(Predicate.IsEmpty(StatePath("emptyObj")).evaluate(store))
        assertFalse(Predicate.IsEmpty(StatePath("full")).evaluate(store))
    }

    @Test
    fun all_requires_every_predicate() {
        val store = storeWith(
            StatePath("a") to JsonPrimitive(true),
            StatePath("b") to JsonPrimitive(1),
        )
        val p = Predicate.All(
            predicates = listOf(
                Predicate.Eq(StatePath("a"), JsonPrimitive(true)),
                Predicate.Eq(StatePath("b"), JsonPrimitive(1)),
            ),
        )
        assertTrue(p.evaluate(store))

        val failing = Predicate.All(
            predicates = listOf(
                Predicate.Eq(StatePath("a"), JsonPrimitive(true)),
                Predicate.Eq(StatePath("b"), JsonPrimitive(99)),
            ),
        )
        assertFalse(failing.evaluate(store))
    }

    @Test
    fun any_requires_at_least_one() {
        val store = storeWith(StatePath("x") to JsonPrimitive("a"))
        val p = Predicate.Any(
            predicates = listOf(
                Predicate.Eq(StatePath("x"), JsonPrimitive("z")),
                Predicate.Eq(StatePath("x"), JsonPrimitive("a")),
            ),
        )
        assertTrue(p.evaluate(store))
    }

    @Test
    fun any_empty_list_is_false() {
        assertFalse(Predicate.Any(emptyList()).evaluate(StateStore()))
    }

    @Test
    fun all_empty_list_is_true() {
        assertTrue(Predicate.All(emptyList()).evaluate(StateStore()))
    }
}
