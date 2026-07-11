package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.Destination
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the new M4 destinations (Modal, TabSwitch, PopToRoot). StackNavigator's switchTab is
 * a no-op until NavHost renderer lands, but PopToRoot and Modal are exercised here.
 */
class NavigatorDestinationTest {

    @Test
    fun pop_to_root_collapses_stack_to_initial() {
        val nav = StackNavigator(initial = "/home")
        nav.push("/a")
        nav.push("/b")
        nav.navigate(Destination.PopToRoot)
        assertEquals(listOf("/home"), nav.snapshot())
    }

    @Test
    fun modal_currently_pushes_like_a_regular_screen() {
        // M4 ships the protocol; full modal chrome is a later milestone. Until then a Modal
        // destination behaves as a push so the rest of the flow still works.
        val nav = StackNavigator(initial = "/home")
        nav.navigate(Destination.Modal(route = "/settings"))
        assertEquals(listOf("/home", "/settings"), nav.snapshot())
    }

    @Test
    fun tab_switch_is_noop_on_stack_navigator() {
        val nav = StackNavigator(initial = "/home")
        nav.navigate(Destination.TabSwitch(tabId = "feed"))
        assertEquals(listOf("/home"), nav.snapshot())
    }
}
