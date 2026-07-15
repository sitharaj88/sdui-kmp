package dev.sdui.kmp.studio.web.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.UnknownUiNode
import dev.sdui.kmp.studio.web.theme.StudioIcons
import dev.sdui.kmp.protocol.Button as ButtonNode
import dev.sdui.kmp.protocol.Column as ColumnNode
import dev.sdui.kmp.protocol.Text as TextNode

/**
 * Right-hand panel that edits the currently-selected node's fields.
 *
 * Per ADR-0019 the inspector dispatches changes by handing the caller a replacement [UiNode]
 * and letting them re-thread it through the workspace's commit; the inspector itself does not
 * know how the tree is wired together. The hand-written per-type `when` (over reflection) is
 * deliberate — token enforcement must be visible at the call site.
 *
 * Coverage: every concrete node type gets a section (see `NodeInspectors.kt`); a common
 * header shows the type, id, and schema version. Structured JSON fields and `Value.Bind` /
 * `Value.Template` show as read-only "edit in JSON tab" chips. Accessibility (`a11y`) editing
 * is deferred — also a JSON-tab chip for now.
 */
@Composable
@Suppress("FunctionNaming", "CyclomaticComplexMethod")
public fun PropertyInspector(
    selected: UiNode?,
    onChange: (UiNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected == null) {
            Text(
                text = "Select a node in the canvas or layers panel to edit its properties.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        CommonHeader(selected)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (selected) {
            is ColumnNode -> ColumnInspector(selected, onChange)
            is TextNode -> TextInspector(selected, onChange)
            is ButtonNode -> ButtonInspector(selected, onChange)
            is TextField -> TextFieldInspector(selected, onChange)
            is Checkbox -> CheckboxInspector(selected, onChange)
            is Image -> ImageInspector(selected, onChange)
            is AsyncImage -> AsyncImageInspector(selected, onChange)
            is LazyList -> LazyListInspector(selected, onChange)
            is NavHost -> NavHostInspector(selected, onChange)
            is NativeSurface -> NativeSurfaceInspector(selected, onChange)
            is UnknownUiNode -> UnknownInspector(selected)
            else -> Text(
                text = "No editable fields for ${selected::class.simpleName} yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        JsonEscapeChip("a11y")
    }
}

@Composable
private fun CommonHeader(node: UiNode) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = descriptorFor(node)?.icon ?: StudioIcons.NodeUnknown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(HEADER_ICON_SIZE),
        )
        Text(
            text = node::class.simpleName ?: "Node",
            style = MaterialTheme.typography.titleSmall,
        )
    }
    ReadOnlyRow("id", node.id.value)
    ReadOnlyRow("since", "v${node.since.value}")
}

private val HEADER_ICON_SIZE = 16.dp
