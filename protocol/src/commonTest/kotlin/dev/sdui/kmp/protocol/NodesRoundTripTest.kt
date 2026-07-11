package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class NodesRoundTripTest {
    private val json: Json = SduiJson

    @Test
    fun text_roundtrips_with_literal_content() {
        val original: UiNode = Text(
            id = NodeId("greeting"),
            content = Value.ofString("hello"),
            style = TextStyleToken.Heading,
            color = ColorToken.Primary,
        )
        val encoded = json.encodeToString(UiNode.serializer(), original)
        assertEquals(original, json.decodeFromString(UiNode.serializer(), encoded))
    }

    @Test
    fun button_roundtrips_with_navigate_action() {
        val original: UiNode = Button(
            id = NodeId("cta"),
            label = Value.ofString("Go"),
            action = Action.Navigate(Destination.ScreenDest("/next")),
            style = ButtonStyle.Primary,
        )
        val encoded = json.encodeToString(UiNode.serializer(), original)
        assertEquals(original, json.decodeFromString(UiNode.serializer(), encoded))
    }

    @Test
    fun column_roundtrips_with_nested_children() {
        val original: UiNode = Column(
            id = NodeId("root"),
            spacing = Spacing.Md,
            padding = EdgeInsets.all(Spacing.Sm),
            children = listOf(
                Text(id = NodeId("t1"), content = Value.ofString("one")),
                Text(id = NodeId("t2"), content = Value.Bind(StatePath("greeting"))),
            ),
        )
        val encoded = json.encodeToString(UiNode.serializer(), original)
        assertEquals(original, json.decodeFromString(UiNode.serializer(), encoded))
    }

    @Test
    fun fallback_tree_roundtrips() {
        val original: UiNode = Button(
            id = NodeId("b"),
            label = Value.ofString("btn"),
            action = Action.Navigate(Destination.Back()),
            fallback = Text(id = NodeId("fallback"), content = Value.ofString("unsupported")),
        )
        val encoded = json.encodeToString(UiNode.serializer(), original)
        val decoded = json.decodeFromString(UiNode.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun unknown_discriminator_decodes_to_unknown_ui_node() {
        val rogue = """
            {
              "type": "radical_new_widget",
              "id": "x1",
              "since": 42,
              "fallback": {
                "type": "text",
                "id": "x1.fallback",
                "content": { "type": "literal", "value": "older clients see me" }
              }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(UiNode.serializer(), rogue)
        val unknown = assertIs<UnknownUiNode>(decoded)
        assertEquals("radical_new_widget", unknown.originalType)
        assertEquals(NodeId("x1"), unknown.id)
        assertEquals(SchemaVersion(42), unknown.since)
        val fallback = assertIs<Text>(unknown.fallback)
        assertEquals("x1.fallback", fallback.id.value)
    }

    @Test
    fun unknown_discriminator_without_fallback_decodes_without_throwing() {
        val rogue = """{"type":"something","id":"q"}"""
        val decoded = json.decodeFromString(UiNode.serializer(), rogue)
        val unknown = assertIs<UnknownUiNode>(decoded)
        assertEquals("something", unknown.originalType)
        assertNull(unknown.fallback)
    }
}
