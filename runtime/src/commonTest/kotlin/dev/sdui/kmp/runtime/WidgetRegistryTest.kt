package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.UnknownUiNode
import dev.sdui.kmp.protocol.Value
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WidgetRegistryTest {
    private object StubTextRenderer : NodeRenderer<Text> {
        override val nodeClass: KClass<Text> = Text::class
        override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion.V1
        @Composable override fun Render(node: Text, modifier: Modifier) {}
    }

    @Test
    fun registered_text_resolves_to_its_renderer() {
        val registry = WidgetRegistry.build(clientVersion = SchemaVersion.V1) {
            register(StubTextRenderer)
        }
        val node: UiNode = Text(id = NodeId("t"), content = Value.ofString("hi"))
        assertNotNull(registry.rendererFor(node))
    }

    @Test
    fun unknown_ui_node_has_no_renderer() {
        val registry = WidgetRegistry.build(clientVersion = SchemaVersion.V1) {
            register(StubTextRenderer)
        }
        val node: UiNode = UnknownUiNode(id = NodeId("u"), originalType = "mystery")
        assertNull(registry.rendererFor(node))
    }

    @Test
    fun client_newer_than_handled_range_falls_through_to_null() {
        val registry = WidgetRegistry.build(clientVersion = SchemaVersion(99)) {
            register(StubTextRenderer)
        }
        val node: UiNode = Text(id = NodeId("t"), content = Value.ofString("hi"))
        assertNull(registry.rendererFor(node))
    }
}
