package dev.sdui.kmp.server

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerDslTest {

    private fun homeScreen(): Screen = screen(id = "home") {
        column {
            text("Welcome", style = dev.sdui.kmp.protocol.TextStyleToken.Heading)
            text("Welcome to sdui-kmp")
            button(
                label = "Go",
                action = Action.Navigate(Destination.ScreenDest("/about")),
            )
        }
    }

    @Test
    fun two_runs_produce_byte_identical_json() {
        val first = SduiServerJson.encodeToString(Screen.serializer(), homeScreen())
        val second = SduiServerJson.encodeToString(Screen.serializer(), homeScreen())
        assertEquals(first, second)
    }

    @Test
    fun ids_are_deterministic_and_structured() {
        val screen = homeScreen()
        val root = assertIs<Column>(screen.root)
        assertEquals("home/column[0]", root.id.value)
        assertEquals(3, root.children.size)
        val text0 = assertIs<Text>(root.children[0])
        val text1 = assertIs<Text>(root.children[1])
        val button = assertIs<Button>(root.children[2])
        assertEquals("home/column[0]/text[0]", text0.id.value)
        assertEquals("home/column[0]/text[1]", text1.id.value)
        assertEquals("home/column[0]/button[0]", button.id.value)
    }

    @Test
    fun single_child_becomes_root_without_extra_wrapper() {
        val screen = screen("about") { text("About") }
        assertIs<Text>(screen.root)
    }

    @Test
    fun multiple_top_level_children_get_wrapped_in_column() {
        val screen = screen("multi") {
            text("a")
            text("b")
        }
        val root = assertIs<Column>(screen.root)
        assertEquals("multi/root", root.id.value)
        assertTrue(root.children.size == 2)
    }

    @Test
    fun roundtrip_through_json_preserves_tree() {
        val screen = homeScreen()
        val encoded = SduiServerJson.encodeToString(Screen.serializer(), screen)
        val decoded = SduiServerJson.decodeFromString(Screen.serializer(), encoded)
        assertEquals(screen, decoded)
    }
}
