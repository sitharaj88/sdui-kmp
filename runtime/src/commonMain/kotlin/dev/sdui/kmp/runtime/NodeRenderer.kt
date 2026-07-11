package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.UiNode
import kotlin.reflect.KClass

/**
 * Renders a concrete [UiNode] subclass into Compose. Registered per widget module into a
 * [WidgetRegistry]; never invoked by a `when` block on node type.
 *
 * [handledVersions] lets the same client ship multiple renderers for the same node class
 * during a migration window.
 */
public interface NodeRenderer<T : UiNode> {
    public val nodeClass: KClass<T>
    public val handledVersions: ClosedRange<SchemaVersion>

    @Composable
    public fun Render(node: T, modifier: Modifier)
}
