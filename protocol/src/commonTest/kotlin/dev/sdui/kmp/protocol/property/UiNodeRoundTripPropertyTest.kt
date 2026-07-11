package dev.sdui.kmp.protocol.property

import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.UiNode
import io.kotest.property.PropertyTesting
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Property tests asserting structural equality across the encode/decode boundary for the focused
 * widget subset called out in `docs/adr/0014-property-based-protocol-tests.md`.
 *
 * Each `@Test` body is a coroutine that drives kotest-property's `checkAll` — kotest-property
 * is brought in as a library only, so we deliberately do NOT extend `StringSpec` /
 * `FunSpec`. The wrapper bridges `runTest` and `checkAll` so the existing kotlin.test
 * runner picks up these tests on every target the protocol module compiles for.
 */
class UiNodeRoundTripPropertyTest {
    private val json: Json = SduiJson

    init {
        // Bound the input volume so the property suite stays well under a second on the
        // tightest CI runner. Increase locally with -Pkotest.iterations to fuzz harder.
        PropertyTesting.defaultIterationCount = 64
    }

    @Test
    fun text_node_roundtrips() = runTest {
        checkAll(arbText) { node ->
            val encoded = json.encodeToString(UiNode.serializer(), node)
            val decoded = json.decodeFromString(UiNode.serializer(), encoded)
            assertEquals(node, decoded)
        }
    }

    @Test
    fun button_node_roundtrips() = runTest {
        checkAll(arbButton) { node ->
            val encoded = json.encodeToString(UiNode.serializer(), node)
            val decoded = json.decodeFromString(UiNode.serializer(), encoded)
            assertEquals(node, decoded)
        }
    }

    @Test
    fun image_node_roundtrips() = runTest {
        checkAll(arbImage) { node ->
            val encoded = json.encodeToString(UiNode.serializer(), node)
            val decoded = json.decodeFromString(UiNode.serializer(), encoded)
            assertEquals(node, decoded)
        }
    }

    @Test
    fun async_image_node_roundtrips() = runTest {
        checkAll(arbAsyncImageLeaf) { node ->
            val encoded = json.encodeToString(UiNode.serializer(), node)
            val decoded = json.decodeFromString(UiNode.serializer(), encoded)
            assertEquals(node, decoded)
        }
    }

    @Test
    fun container_with_nested_children_roundtrips() = runTest {
        checkAll(arbContainer) { node ->
            val encoded = json.encodeToString(UiNode.serializer(), node)
            val decoded = json.decodeFromString(UiNode.serializer(), encoded)
            assertEquals(node, decoded)
        }
    }

    @Test
    fun arbitrary_bounded_tree_roundtrips() = runTest {
        checkAll(arbUiNode()) { node ->
            val encoded = json.encodeToString(UiNode.serializer(), node)
            val decoded = json.decodeFromString(UiNode.serializer(), encoded)
            assertEquals(node, decoded)
        }
    }
}
