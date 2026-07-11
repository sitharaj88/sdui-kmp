package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Destination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StackNavigatorTest {
    @Test
    fun empty_stack_has_null_current() {
        val nav = StackNavigator()
        assertNull(nav.current.value)
    }

    @Test
    fun push_updates_current_and_appends() {
        val nav = StackNavigator(initial = "/home")
        nav.push("/about")
        assertEquals("/about", nav.current.value)
        assertEquals(listOf("/home", "/about"), nav.snapshot())
    }

    @Test
    fun pop_removes_top_and_updates_current() {
        val nav = StackNavigator(initial = "/home")
        nav.push("/about")
        nav.pop()
        assertEquals("/home", nav.current.value)
        assertEquals(listOf("/home"), nav.snapshot())
    }

    @Test
    fun pop_multiple_levels() {
        val nav = StackNavigator(initial = "/a")
        nav.push("/b")
        nav.push("/c")
        nav.pop(2)
        assertEquals(listOf("/a"), nav.snapshot())
    }

    @Test
    fun pop_below_root_leaves_empty_stack() {
        val nav = StackNavigator(initial = "/a")
        nav.pop(5)
        assertEquals(emptyList(), nav.snapshot())
    }

    @Test
    fun replace_swaps_the_top() {
        val nav = StackNavigator(initial = "/a")
        nav.push("/b")
        nav.replace("/c")
        assertEquals(listOf("/a", "/c"), nav.snapshot())
    }

    @Test
    fun popToRoot_keeps_first_frame() {
        val nav = StackNavigator(initial = "/home")
        nav.push("/a")
        nav.push("/b")
        nav.popToRoot()
        assertEquals(listOf("/home"), nav.snapshot())
    }

    @Test
    fun navigate_routes_through_destination_kind() {
        val nav = StackNavigator(initial = "/home")
        nav.navigate(Destination.ScreenDest("/about"))
        assertEquals(listOf("/home", "/about"), nav.snapshot())
        nav.navigate(Destination.Back(count = 1))
        assertEquals(listOf("/home"), nav.snapshot())
    }

    @Test
    fun navigate_with_replace_swaps_instead_of_pushing() {
        val nav = StackNavigator(initial = "/home")
        nav.navigate(Destination.ScreenDest("/login"), replace = true)
        assertEquals(listOf("/login"), nav.snapshot())
    }
}
