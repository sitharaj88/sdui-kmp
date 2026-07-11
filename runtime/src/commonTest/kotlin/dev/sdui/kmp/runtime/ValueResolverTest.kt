package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive

class ValueResolverTest {

    @Test
    fun literal_returns_primitive_content() {
        assertEquals("hi", (Value.ofString("hi") as Value<String>).resolve(StateStore()))
    }

    @Test
    fun bind_reads_from_store_falls_back_to_empty() {
        val store = StateStore(mapOf(StatePath("name") to JsonPrimitive("alice")))
        val v: Value<String> = Value.Bind(StatePath("name"))
        assertEquals("alice", v.resolve(store))

        val missing: Value<String> = Value.Bind(StatePath("other"))
        assertEquals("", missing.resolve(store))
    }

    @Test
    fun template_substitutes_known_placeholders() {
        val store = StateStore(
            mapOf(
                StatePath("user.name") to JsonPrimitive("alice"),
                StatePath("user.age") to JsonPrimitive("42"),
            ),
        )
        val v: Value<String> = Value.template(
            pattern = "Hello {name}, age {age}",
            bindings = mapOf(
                "name" to StatePath("user.name"),
                "age" to StatePath("user.age"),
            ),
        )
        assertEquals("Hello alice, age 42", v.resolve(store))
    }

    @Test
    fun template_leaves_unknown_placeholder_in_place() {
        val v: Value<String> = Value.template("Hi {name}, ready? {flag}", mapOf("name" to StatePath("n")))
        val store = StateStore(mapOf(StatePath("n") to JsonPrimitive("Alice")))
        assertEquals("Hi Alice, ready? {flag}", v.resolve(store))
    }

    @Test
    fun template_substitutes_missing_bindings_with_empty_string() {
        val v: Value<String> = Value.template("Hi {name}", mapOf("name" to StatePath("missing")))
        assertEquals("Hi ", v.resolve(StateStore()))
    }

    @Test
    fun template_avoids_prefix_collision_between_keys() {
        // `{a}` must not be substituted inside `{ab}`.
        val v: Value<String> = Value.template(
            pattern = "{a}-{ab}",
            bindings = mapOf("a" to StatePath("x"), "ab" to StatePath("y")),
        )
        val store = StateStore(
            mapOf(
                StatePath("x") to JsonPrimitive("ONE"),
                StatePath("y") to JsonPrimitive("TWO"),
            ),
        )
        assertEquals("ONE-TWO", v.resolve(store))
    }
}
