package dev.sdui.kmp.protocol.property

import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.Value
import io.kotest.property.PropertyTesting
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Property tests for the four `Value<T>` shapes that ride on the wire today.
 *
 * Every wire-side roundtrip drives both the [Value.Literal] decoder (which reads a
 * `JsonElement` per ADR-0001) and the typed serializer chosen by the call site, so a
 * regression in either path will fail one of these checks.
 */
class ValueRoundTripPropertyTest {
    private val json: Json = SduiJson

    init {
        PropertyTesting.defaultIterationCount = 64
    }

    @Test
    fun string_literal_roundtrips() = runTest {
        val serializer = Value.serializer(String.serializer())
        checkAll(arbStringLiteral) { value ->
            val encoded = json.encodeToString(serializer, value)
            val decoded = json.decodeFromString(serializer, encoded)
            assertEquals(value, decoded)
        }
    }

    @Test
    fun int_literal_roundtrips() = runTest {
        val serializer = Value.serializer(Int.serializer())
        checkAll(arbIntLiteral) { value ->
            val encoded = json.encodeToString(serializer, value)
            val decoded = json.decodeFromString(serializer, encoded)
            assertEquals(value, decoded)
        }
    }

    @Test
    fun boolean_literal_roundtrips() = runTest {
        val serializer = Value.serializer(Boolean.serializer())
        checkAll(arbBooleanLiteral) { value ->
            val encoded = json.encodeToString(serializer, value)
            val decoded = json.decodeFromString(serializer, encoded)
            assertEquals(value, decoded)
        }
    }

    @Test
    fun string_bind_roundtrips() = runTest {
        val serializer = Value.serializer(String.serializer())
        checkAll(arbStringBind) { value ->
            val encoded = json.encodeToString(serializer, value)
            val decoded = json.decodeFromString(serializer, encoded)
            assertEquals(value, decoded)
        }
    }

    @Test
    fun string_template_roundtrips() = runTest {
        val serializer = Value.serializer(String.serializer())
        checkAll(arbStringTemplate) { value ->
            val encoded = json.encodeToString(serializer, value)
            val decoded = json.decodeFromString(serializer, encoded)
            assertEquals(value, decoded)
        }
    }

    @Test
    fun arbitrary_string_value_roundtrips() = runTest {
        val serializer = Value.serializer(String.serializer())
        checkAll(arbStringValue) { value ->
            val encoded = json.encodeToString(serializer, value)
            val decoded = json.decodeFromString(serializer, encoded)
            assertEquals(value, decoded)
        }
    }
}
