package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ListAndNavRoundTripTest {
    private val json: Json = SduiJson

    @Test
    fun lazy_list_inline_roundtrips() {
        val node: UiNode = LazyList(
            id = NodeId("feed"),
            source = ListSource.Inline(
                items = listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive("a"))
                        put("title", JsonPrimitive("Alpha"))
                    },
                    buildJsonObject {
                        put("id", JsonPrimitive("b"))
                        put("title", JsonPrimitive("Beta"))
                    },
                ),
            ),
            itemTemplate = Text(id = NodeId("row"), content = Value.Bind(StatePath("title"))),
            itemKeyPath = StatePath("id"),
            spacing = Spacing.Sm,
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun lazy_list_bound_roundtrips() {
        val node: UiNode = LazyList(
            id = NodeId("feed"),
            source = ListSource.Bound(path = StatePath("feed.items")),
            itemTemplate = Text(id = NodeId("row"), content = Value.Bind(StatePath("title"))),
            itemKeyPath = StatePath("id"),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun lazy_list_paged_roundtrips() {
        val node: UiNode = LazyList(
            id = NodeId("feed"),
            source = ListSource.Paged(endpoint = "/feed/items", pageSize = 50, cursor = "abc"),
            itemTemplate = Text(id = NodeId("row"), content = Value.ofString("x")),
            itemKeyPath = StatePath("id"),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun pagination_envelope_roundtrips() {
        val p = Pagination(
            items = listOf(
                buildJsonObject {
                    put("id", JsonPrimitive(1))
                    put("label", JsonPrimitive("one"))
                },
            ),
            nextCursor = "next-page-token",
        )
        assertEquals(p, json.decodeFromString(Pagination.serializer(), json.encodeToString(Pagination.serializer(), p)))
    }

    @Test
    fun nav_host_roundtrips() {
        val node: UiNode = NavHost(
            id = NodeId("app"),
            kind = NavKind.Tab,
            initial = Destination.TabSwitch(tabId = "feed"),
            routes = mapOf(
                "feed" to "/screens/feed",
                "search" to "/screens/search",
                "profile" to "/screens/profile",
            ),
        )
        assertEquals(node, json.decodeFromString(UiNode.serializer(), json.encodeToString(UiNode.serializer(), node)))
    }

    @Test
    fun new_destination_variants_roundtrip() {
        val variants: List<Destination> = listOf(
            Destination.Modal(route = "/settings"),
            Destination.TabSwitch(tabId = "profile"),
            Destination.PopToRoot,
        )
        variants.forEach {
            assertEquals(
                it,
                json.decodeFromString(Destination.serializer(), json.encodeToString(Destination.serializer(), it)),
            )
        }
    }
}
