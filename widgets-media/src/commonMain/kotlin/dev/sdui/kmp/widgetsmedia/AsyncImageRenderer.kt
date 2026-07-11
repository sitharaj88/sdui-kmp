package dev.sdui.kmp.widgetsmedia

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.AsyncImage as AsyncImageNode
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.RenderNode
import dev.sdui.kmp.runtime.applyA11y
import dev.sdui.kmp.runtime.resolve
import kotlin.reflect.KClass

public object AsyncImageRenderer : NodeRenderer<AsyncImageNode> {
    override val nodeClass: KClass<AsyncImageNode> = AsyncImageNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: AsyncImageNode, modifier: Modifier) {
        val loader = LocalImageLoader.current
        val store = LocalStateStore.current
        @Suppress("UNUSED_VARIABLE") val subscribe = store.snapshot.value
        loader.AsyncImage(
            url = node.url.resolve(store),
            contentDescription = node.contentDescription?.resolve(store),
            contentScale = node.contentScale,
            placeholder = node.placeholder?.let { subtree -> subtreeRenderer(subtree) },
            error = node.error?.let { subtree -> subtreeRenderer(subtree) },
            modifier = modifier.applyA11y(node.a11y, store),
        )
    }

    private fun subtreeRenderer(subtree: UiNode): @Composable (Modifier) -> Unit = { modifier ->
        RenderNode(subtree, modifier)
    }
}
