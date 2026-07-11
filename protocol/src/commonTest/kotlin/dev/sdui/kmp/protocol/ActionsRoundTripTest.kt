package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ActionsRoundTripTest {
    private val json: Json = SduiJson

    @Test
    fun navigate_to_screen_dest_roundtrips_byte_identical() {
        val action: Action = Action.Navigate(
            destination = Destination.ScreenDest(route = "/home"),
            replace = false,
        )
        val first = json.encodeToString(Action.serializer(), action)
        val decoded = json.decodeFromString(Action.serializer(), first)
        val second = json.encodeToString(Action.serializer(), decoded)
        assertEquals(first, second)
        assertEquals(action, decoded)
    }

    @Test
    fun navigate_back_with_count_roundtrips() {
        val action: Action = Action.Navigate(Destination.Back(count = 2))
        val encoded = json.encodeToString(Action.serializer(), action)
        assertEquals(action, json.decodeFromString(Action.serializer(), encoded))
    }

    @Test
    fun update_state_literal_roundtrips() {
        val action: Action = Action.UpdateState(
            path = StatePath("form.username"),
            value = Value.ofJson(JsonPrimitive("alice")),
        )
        val encoded = json.encodeToString(Action.serializer(), action)
        assertEquals(action, json.decodeFromString(Action.serializer(), encoded))
    }

    @Test
    fun screen_dest_with_args_roundtrips() {
        val dest: Destination = Destination.ScreenDest(
            route = "/profile",
            args = buildJsonObject { put("userId", JsonPrimitive("u-42")) },
        )
        val encoded = json.encodeToString(Destination.serializer(), dest)
        assertEquals(dest, json.decodeFromString(Destination.serializer(), encoded))
    }

    @Test
    fun predicate_nested_roundtrips() {
        val predicate: Predicate = Predicate.All(
            predicates = listOf(
                Predicate.Eq(StatePath("ready"), JsonPrimitive(true)),
                Predicate.Not(Predicate.IsEmpty(StatePath("items"))),
                Predicate.Any(
                    predicates = listOf(
                        Predicate.Eq(StatePath("tier"), JsonPrimitive("gold")),
                        Predicate.Eq(StatePath("tier"), JsonPrimitive("platinum")),
                    ),
                ),
            ),
        )
        val encoded = json.encodeToString(Predicate.serializer(), predicate)
        assertEquals(predicate, json.decodeFromString(Predicate.serializer(), encoded))
    }

    @Test
    fun value_bind_and_literal_roundtrip() {
        val literal: Value<String> = Value.ofString("hi")
        val bound: Value<String> = Value.Bind(StatePath("greeting"))
        // Use a concrete T (String) to invoke the generic serializer; wire form is the same.
        val ser = Value.serializer(String.serializer())
        assertEquals(literal, json.decodeFromString(ser, json.encodeToString(ser, literal)))
        assertEquals(bound, json.decodeFromString(ser, json.encodeToString(ser, bound)))
    }
}
