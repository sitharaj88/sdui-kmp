package dev.sdui.kmp.widgetsmedia

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.Image as ImageNode
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.StateStore
import dev.sdui.kmp.runtime.applyA11y
import dev.sdui.kmp.runtime.resolve
import kotlin.reflect.KClass

public object ImageRenderer : NodeRenderer<ImageNode> {
    override val nodeClass: KClass<ImageNode> = ImageNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: ImageNode, modifier: Modifier) {
        val loader = LocalImageLoader.current
        val store = dev.sdui.kmp.runtime.LocalStateStore.current
        @Suppress("UNUSED_VARIABLE") val subscribe = store.snapshot.value
        loader.Image(
            source = node.source.resolveWith(store),
            contentDescription = node.contentDescription?.resolveWith(store),
            contentScale = node.contentScale,
            modifier = modifier.applyA11y(node.a11y, store),
        )
    }
}

internal fun dev.sdui.kmp.protocol.Value<String>.resolveWith(store: StateStore): String = resolve(store)
