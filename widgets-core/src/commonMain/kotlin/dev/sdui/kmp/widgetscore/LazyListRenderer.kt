package dev.sdui.kmp.widgetscore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.LazyList as LazyListNode
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.Orientation
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.runtime.ItemScope
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.RenderNode
import dev.sdui.kmp.runtime.applyA11y
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public object LazyListRenderer : NodeRenderer<LazyListNode> {
    override val nodeClass: KClass<LazyListNode> = LazyListNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: LazyListNode, modifier: Modifier) {
        val items = resolveItems(node)
        if (items.isEmpty()) {
            node.emptyState?.let { RenderNode(it, modifier) }
            return
        }
        val keyPath = node.itemKeyPath
        val insets = node.padding.resolve()
        val padding = PaddingValues(
            start = insets.start,
            top = insets.top,
            end = insets.end,
            bottom = insets.bottom,
        )
        val accessibleModifier = modifier.applyA11y(node.a11y, LocalStateStore.current)
        when (node.orientation) {
            Orientation.Vertical -> LazyColumn(
                modifier = accessibleModifier,
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(node.spacing.toDp()),
            ) {
                items(items = items, key = { itemKeyOf(it, keyPath) }) { item ->
                    ItemScope(itemKey = itemKeyOf(item, keyPath), initial = item) {
                        RenderNode(node.itemTemplate)
                    }
                }
            }
            Orientation.Horizontal -> LazyRow(
                modifier = accessibleModifier,
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(node.spacing.toDp()),
            ) {
                items(items = items, key = { itemKeyOf(it, keyPath) }) { item ->
                    ItemScope(itemKey = itemKeyOf(item, keyPath), initial = item) {
                        RenderNode(node.itemTemplate)
                    }
                }
            }
        }
    }

    @Composable
    private fun resolveItems(node: LazyListNode): List<JsonObject> = when (val source = node.source) {
        is ListSource.Inline -> source.items
        is ListSource.Bound -> {
            val store = LocalStateStore.current
            @Suppress("UNUSED_VARIABLE") val subscribe = store.snapshot.value
            (store.read(source.path) as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?: emptyList()
        }
        is ListSource.Paged -> {
            // M4 ships the protocol; the pager lands with transport hardening. For now an
            // unpaged stub renders empty, which the server can override via `emptyState`.
            emptyList()
        }
        // A list source added by a newer server that this client cannot decode renders empty
        // (the server can still supply `emptyState`) rather than throwing.
        is ListSource.Unknown -> emptyList()
    }

    private fun itemKeyOf(item: JsonObject, keyPath: StatePath): String {
        // Support nested key paths like "profile.id" by walking the object.
        val segments = keyPath.value.split('.').filter { it.isNotEmpty() }
        var cursor: kotlinx.serialization.json.JsonElement? = item
        for (segment in segments) {
            val obj = cursor as? JsonObject ?: return item.hashCode().toString()
            cursor = obj[segment]
        }
        return (cursor as? JsonPrimitive)?.content ?: item.hashCode().toString()
    }
}
