package dev.sdui.kmp.widgetscore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column as ComposeColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.Column as ColumnNode
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.RenderNode
import dev.sdui.kmp.runtime.applyA11y
import kotlin.reflect.KClass

public object ColumnRenderer : NodeRenderer<ColumnNode> {
    override val nodeClass: KClass<ColumnNode> = ColumnNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: ColumnNode, modifier: Modifier) {
        val insets = node.padding.resolve()
        ComposeColumn(
            modifier = modifier
                .applyA11y(node.a11y, LocalStateStore.current)
                .padding(
                    PaddingValues(
                        start = insets.start,
                        top = insets.top,
                        end = insets.end,
                        bottom = insets.bottom,
                    ),
                ),
            verticalArrangement = Arrangement.spacedBy(node.spacing.toDp()),
        ) {
            node.children.forEach { child ->
                RenderNode(child)
            }
        }
    }
}
