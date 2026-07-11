package dev.sdui.kmp.protocol.property

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.IconToken
import dev.sdui.kmp.protocol.Keyboard
import dev.sdui.kmp.protocol.LiveEvent
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.NavKind
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.Predicate
import dev.sdui.kmp.protocol.RetryPolicy
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Companion to [UnknownNodeFallbackPropertyTest] that extends the "never crash on an unknown
 * wire type" guarantee to *every* wire-crossing sealed hierarchy — not just [dev.sdui.kmp.protocol.UiNode].
 *
 * A newer server that adds an [Action], [Value], [Predicate], [Destination], token, or any other
 * polymorphic case must decode to that hierarchy's inert `Unknown` sentinel on an older client,
 * never throw. Unknown enum values must coerce to their field's default. This locks in the third
 * VISION non-negotiable across the whole protocol surface.
 */
class UnknownSealedFallbackPropertyTest {
    private val json = SduiJson

    init {
        PropertyTesting.defaultIterationCount = 64
    }

    /**
     * Discriminators prefixed `zz_` cannot collide with any real `@SerialName` in the protocol
     * (all of which are lower-snake-case words) nor with the `__unknown__` sentinel names.
     */
    private val arbUnknownDiscriminator: Arb<String> =
        Arb.string(minSize = 1, maxSize = 16)
            .map { raw -> "zz_" + raw.replace(Regex("[^A-Za-z0-9_]"), "x") }
            .filter { it.isNotBlank() }

    private fun objectWithType(discriminator: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive(discriminator)))

    private fun <T> decode(serializer: DeserializationStrategy<T>, discriminator: String): T =
        json.decodeFromJsonElement(serializer, objectWithType(discriminator))

    @Test
    fun unknown_discriminator_in_every_sealed_hierarchy_decodes_to_its_sentinel_without_throwing() = runTest {
        checkAll(arbUnknownDiscriminator) { d ->
            assertEquals(d, assertIs<Action.Unknown>(decode(Action.serializer(), d)).originalType)
            assertEquals(d, assertIs<Value.Unknown>(decode(Value.serializer(String.serializer()), d)).originalType)
            assertEquals(d, assertIs<Predicate.Unknown>(decode(Predicate.serializer(), d)).originalType)
            assertEquals(d, assertIs<Validation.Unknown>(decode(Validation.serializer(), d)).originalType)
            assertEquals(d, assertIs<Destination.Unknown>(decode(Destination.serializer(), d)).originalType)
            assertEquals(d, assertIs<RetryPolicy.Unknown>(decode(RetryPolicy.serializer(), d)).originalType)
            assertEquals(d, assertIs<PatchOp.Unknown>(decode(PatchOp.serializer(), d)).originalType)
            assertEquals(d, assertIs<ListSource.Unknown>(decode(ListSource.serializer(), d)).originalType)
            assertEquals(d, assertIs<LiveEvent.Unknown>(decode(LiveEvent.serializer(), d)).originalType)
            assertEquals(d, assertIs<ColorToken.Unknown>(decode(ColorToken.serializer(), d)).originalType)
            assertEquals(d, assertIs<IconToken.Unknown>(decode(IconToken.serializer(), d)).originalType)
        }
    }

    @Test
    fun unknown_discriminator_ignores_arbitrary_extra_fields() = runTest {
        checkAll(arbUnknownDiscriminator) { d ->
            val obj = JsonObject(
                mapOf(
                    "type" to JsonPrimitive(d),
                    "some_new_field" to JsonPrimitive("value"),
                    "another" to JsonPrimitive(42),
                ),
            )
            val decoded = json.decodeFromJsonElement(Action.serializer(), obj)
            assertEquals(d, assertIs<Action.Unknown>(decoded).originalType)
        }
    }

    // --- Unknown enum values coerce to the field default ------------------------------------------

    private fun retype(element: JsonElement, field: String, value: String): JsonObject {
        val members = element.jsonObject.toMutableMap()
        members[field] = JsonPrimitive(value)
        return JsonObject(members)
    }

    @Test
    fun unknown_button_style_enum_coerces_to_default_without_throwing() {
        val button = Button(
            id = NodeId("b"),
            label = Value.ofString("Tap"),
            action = Action.Navigate(Destination.Back()),
        )
        val tampered = retype(json.encodeToJsonElement(Button.serializer(), button), "style", "zz_neon")
        val decoded = json.decodeFromJsonElement(Button.serializer(), tampered)
        assertEquals(ButtonStyle.Primary, decoded.style)
    }

    @Test
    fun unknown_keyboard_enum_coerces_to_default_without_throwing() {
        val field = TextField(id = NodeId("t"), path = StatePath("form.name"))
        val tampered = retype(json.encodeToJsonElement(TextField.serializer(), field), "keyboard", "zz_biometric")
        val decoded = json.decodeFromJsonElement(TextField.serializer(), tampered)
        assertEquals(Keyboard.Text, decoded.keyboard)
    }

    @Test
    fun unknown_nav_kind_enum_coerces_to_unspecified_default_without_throwing() {
        val navHost = NavHost(id = NodeId("n"), kind = NavKind.Tab, initial = Destination.Back())
        val tampered = retype(json.encodeToJsonElement(NavHost.serializer(), navHost), "kind", "zz_drawer")
        val decoded = json.decodeFromJsonElement(NavHost.serializer(), tampered)
        assertEquals(NavKind.Unspecified, decoded.kind)
    }
}
