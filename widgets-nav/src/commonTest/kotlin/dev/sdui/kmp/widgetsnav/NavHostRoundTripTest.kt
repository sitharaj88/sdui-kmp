package dev.sdui.kmp.widgetsnav

import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.NavKind
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.SduiJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trips a `NavHost(kind = Tab)` inside a `Screen` through `SduiJson` to assert the
 * server emission matches what the renderer expects to consume. This is a protocol-level
 * test (no Compose) because the renderer never makes assumptions about the wire format —
 * it operates on the in-memory data class — but a regression that breaks the JSON shape
 * would still break navigation end-to-end.
 */
class NavHostRoundTripTest {
    @Test
    fun navHost_tab_round_trips_through_sdui_json() {
        val original = Screen(
            id = ScreenId("tabs"),
            version = SchemaVersion.V1,
            root = Column(
                id = NodeId("tabs/root"),
                children = listOf(
                    NavHost(
                        id = NodeId("tabs/host"),
                        kind = NavKind.Tab,
                        initial = Destination.TabSwitch("home"),
                        routes = linkedMapOf(
                            "home" to "/home",
                            "feed" to "/feed",
                            "tracking" to "/tracking",
                        ),
                    ),
                ),
            ),
        )
        val encoded = SduiJson.encodeToString(Screen.serializer(), original)
        val decoded = SduiJson.decodeFromString(Screen.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun navHost_bottom_sheet_round_trips() {
        val original = NavHost(
            id = NodeId("sheet"),
            kind = NavKind.BottomSheet,
            initial = Destination.ScreenDest("/details"),
            routes = mapOf("body" to "/details"),
        )
        val encoded = SduiJson.encodeToString(NavHost.serializer(), original)
        val decoded = SduiJson.decodeFromString(NavHost.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun initialTabId_resolves_tab_switch_destination() {
        val node = NavHost(
            id = NodeId("h"),
            kind = NavKind.Tab,
            initial = Destination.TabSwitch("feed"),
            routes = mapOf("home" to "/h", "feed" to "/f"),
        )
        assertEquals("feed", initialTabId(node, listOf("home", "feed")))
    }

    @Test
    fun initialTabId_falls_back_to_first_when_tab_switch_unknown() {
        val node = NavHost(
            id = NodeId("h"),
            kind = NavKind.Tab,
            initial = Destination.TabSwitch("missing"),
            routes = mapOf("home" to "/h", "feed" to "/f"),
        )
        assertEquals("home", initialTabId(node, listOf("home", "feed")))
    }

    @Test
    fun initialTabId_resolves_by_route_for_screen_dest() {
        val node = NavHost(
            id = NodeId("h"),
            kind = NavKind.Tab,
            initial = Destination.ScreenDest("/f"),
            routes = mapOf("home" to "/h", "feed" to "/f"),
        )
        assertEquals("feed", initialTabId(node, listOf("home", "feed")))
    }
}
