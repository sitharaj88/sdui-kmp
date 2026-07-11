package dev.sdui.kmp.widgetsforms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Checkbox as CheckboxNode
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.applyA11y
import dev.sdui.kmp.runtime.resolve
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 48 dp matches WCAG 2.2 SC 2.5.8 (Target Size — Minimum). Material's `Checkbox` is only
 * 20 dp tall on its own, which fails the spec on touch screens; pinning the surrounding
 * `Row` to 48 dp makes the whole label region tappable.
 */
private val MinTouchTargetHeight = 48.dp

public object CheckboxRenderer : NodeRenderer<CheckboxNode> {
    override val nodeClass: KClass<CheckboxNode> = CheckboxNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: CheckboxNode, modifier: Modifier) {
        val store = LocalStateStore.current
        val snapshot by store.snapshot
        val checked = (snapshot[node.path] as? JsonPrimitive)?.booleanOrNull == true
        val label = node.label?.resolve(store)

        // Wrap the whole row in `toggleable` so screen readers announce a single Checkbox
        // node (with the resolved label as accessibility name) rather than a Row + nested
        // Checkbox + Text. Tapping the label flips the value just like tapping the box.
        // The inner [Checkbox] is left as `onCheckedChange = null` so it forwards events
        // to the toggleable parent — without this you get a doubled toggle on accessibility
        // services that synthesise both the row and the inner widget.
        Row(
            modifier = modifier
                .heightIn(min = MinTouchTargetHeight)
                .toggleable(
                    value = checked,
                    onValueChange = { new -> store.update(node.path, JsonPrimitive(new)) },
                    role = Role.Checkbox,
                )
                .applyA11y(node.a11y, store),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
            )
            if (label != null) Text(label)
        }
    }
}
