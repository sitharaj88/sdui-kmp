package dev.sdui.kmp.protocol.property

import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.UnknownUiNode
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/**
 * Property test for the third VISION non-negotiable: the client-side decoder must NEVER
 * throw on an unknown discriminator. Whatever soup of fields the JSON object carries, the
 * outcome is always an [UnknownUiNode] preserving `id`, `since`, and the original
 * discriminator.
 *
 * The arbitraries deliberately avoid generating any of the discriminators registered in
 * [dev.sdui.kmp.protocol.SduiSerializersModule] — those would route to the typed branch.
 */
class UnknownNodeFallbackPropertyTest {
    private val json: Json = SduiJson

    init {
        PropertyTesting.defaultIterationCount = 96
    }

    /** The set of `type` discriminators that the decoder *would* recognise. */
    private val registeredDiscriminators = setOf(
        "column",
        "text",
        "button",
        "image",
        "async_image",
        "lazy_list",
        "nav_host",
        "native",
        "text_field",
        "checkbox",
        "__unknown__",
    )

    private val arbUnknownDiscriminator: Arb<String> =
        Arb.string(minSize = 1, maxSize = 20)
            .map { raw -> raw.replace(Regex("[^A-Za-z0-9_-]"), "x") }
            .filter { it.isNotBlank() && it !in registeredDiscriminators }

    private val arbExtraField: Arb<JsonElement> = Arb.string(maxSize = 8).map { JsonPrimitive(it) }

    private val arbExtraFields: Arb<Map<String, JsonElement>> = Arb.map(
        Arb.string(minSize = 1, maxSize = 6).filter { key ->
            // Don't accidentally collide with the structural fields we set explicitly.
            key !in setOf("type", "id", "since", "fallback")
        },
        arbExtraField,
        minSize = 0,
        maxSize = 4,
    )

    @Test
    fun unknown_discriminator_decodes_to_unknown_ui_node_without_throwing() = runTest {
        checkAll(
            arbUnknownDiscriminator,
            Arb.string(minSize = 0, maxSize = 8).orNull(0.2),
            Arb.int(min = 0, max = 99).orNull(0.2),
            arbExtraFields,
        ) { discriminator, idOrNull, sinceOrNull, extras ->
            val members: MutableMap<String, JsonElement> = mutableMapOf("type" to JsonPrimitive(discriminator))
            if (idOrNull != null) members["id"] = JsonPrimitive(idOrNull)
            if (sinceOrNull != null) members["since"] = JsonPrimitive(sinceOrNull)
            members += extras
            val element = JsonObject(members)
            val decoded = json.decodeFromJsonElement(UiNode.serializer(), element)
            val unknown = assertIs<UnknownUiNode>(decoded)
            assertEquals(discriminator, unknown.originalType)
            if (idOrNull != null) assertEquals(idOrNull, unknown.id.value)
            if (sinceOrNull != null) assertEquals(sinceOrNull, unknown.since.value)
        }
    }

    @Test
    fun unknown_discriminator_with_arbitrary_fallback_subtree_decodes_without_throwing() = runTest {
        // Build a JsonObject whose `fallback` field is itself a known node so the decoder
        // descends into the registered branch on the way down. This exercises the
        // "unknown wraps known fallback" case — the most common upgrade scenario.
        checkAll(
            arbUnknownDiscriminator,
            arbText,
            arbExtraFields,
        ) { discriminator, fallback, extras ->
            val fallbackElement = json.encodeToJsonElement(UiNode.serializer(), fallback)
            val members: MutableMap<String, JsonElement> = mutableMapOf(
                "type" to JsonPrimitive(discriminator),
                "id" to JsonPrimitive("fallback-host"),
                "since" to JsonPrimitive(99),
                "fallback" to fallbackElement,
            )
            members += extras
            val element = JsonObject(members)
            val decoded = json.decodeFromJsonElement(UiNode.serializer(), element)
            val unknown = assertIs<UnknownUiNode>(decoded)
            assertEquals(discriminator, unknown.originalType)
            assertEquals(fallback, unknown.fallback)
        }
    }

    @Test
    fun unknown_discriminator_with_explicit_null_fallback_decodes_without_throwing() = runTest {
        checkAll(arbUnknownDiscriminator) { discriminator ->
            val element = JsonObject(
                mapOf(
                    "type" to JsonPrimitive(discriminator),
                    "id" to JsonPrimitive("solo"),
                    "fallback" to JsonNull,
                ),
            )
            val decoded = json.decodeFromJsonElement(UiNode.serializer(), element)
            val unknown = assertIs<UnknownUiNode>(decoded)
            assertEquals(discriminator, unknown.originalType)
        }
    }

    // Reference unused arbitraries so kotlin-test's compile-time wired-test detection
    // doesn't think these helpers are dead.
    @Suppress("unused")
    private val arbExtraFieldRef: Arb<List<JsonElement>> = Arb.list(arbExtraField, 0..1)
}
