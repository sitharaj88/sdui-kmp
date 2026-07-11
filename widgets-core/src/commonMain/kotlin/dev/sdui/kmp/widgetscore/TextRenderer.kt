package dev.sdui.kmp.widgetscore

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Text as TextNode
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.applyA11y
import kotlin.reflect.KClass

public object TextRenderer : NodeRenderer<TextNode> {
    override val nodeClass: KClass<TextNode> = TextNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: TextNode, modifier: Modifier) {
        val content = node.content.resolveString()
        val color = node.color?.toComposeColor() ?: LocalContentColor.current
        Text(
            text = content,
            modifier = modifier.applyA11y(node.a11y, LocalStateStore.current),
            color = color,
            style = node.style.toTextStyle(),
        )
    }
}
