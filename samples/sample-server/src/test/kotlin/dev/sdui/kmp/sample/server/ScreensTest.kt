package dev.sdui.kmp.sample.server

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScreensTest {
    @Test
    fun home_screen_has_about_navigate_button() {
        val home = homeScreen()
        val root = assertIs<Column>(home.root)
        val aboutButton = root.children.filterIsInstance<Button>().first {
            val dest = (it.action as? Action.Navigate)?.destination as? Destination.ScreenDest
            dest?.route == "/about"
        }
        val action = assertIs<Action.Navigate>(aboutButton.action)
        val dest = assertIs<Destination.ScreenDest>(action.destination)
        assertEquals("/about", dest.route)
    }

    @Test
    fun about_screen_has_back_button() {
        val about = aboutScreen()
        val root = assertIs<Column>(about.root)
        val button = root.children.filterIsInstance<Button>().single()
        val action = assertIs<Action.Navigate>(button.action)
        assertIs<Destination.Back>(action.destination)
    }

    @Test
    fun login_screen_has_submit_action() {
        val login = loginScreen()
        val root = assertIs<Column>(login.root)
        val submitBtn = root.children.filterIsInstance<Button>().single()
        val submit = assertIs<Action.Submit>(submitBtn.action)
        assertEquals("/auth/login", submit.endpoint)
        assertEquals(3, submit.payload.size)
    }

    @Test
    fun feed_screen_has_lazy_list_with_eight_inline_items() {
        val feed = feedScreen()
        val root = assertIs<Column>(feed.root)
        val list = root.children.filterIsInstance<LazyList>().single()
        val source = assertIs<ListSource.Inline>(list.source)
        assertEquals(8, source.items.size)
        assertEquals("id", list.itemKeyPath.value)
    }

    @Test
    fun tracking_screen_has_native_surface_and_async_image() {
        val tracking = trackingScreen()
        val root = assertIs<Column>(tracking.root)
        val native = root.children.filterIsInstance<dev.sdui.kmp.protocol.NativeSurface>().single()
        assertEquals("sdui.map", native.kind)
        assertEquals(1, root.children.filterIsInstance<dev.sdui.kmp.protocol.AsyncImage>().size)
    }

    @Test
    fun screens_roundtrip_through_json() {
        listOf(homeScreen(), aboutScreen(), loginScreen(), feedScreen(), trackingScreen()).forEach { screen ->
            val encoded = SduiJson.encodeToString(Screen.serializer(), screen)
            val decoded = SduiJson.decodeFromString(Screen.serializer(), encoded)
            assertEquals(screen, decoded)
        }
    }
}
