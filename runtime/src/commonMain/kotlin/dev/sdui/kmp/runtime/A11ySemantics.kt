package dev.sdui.kmp.runtime

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.sdui.kmp.protocol.A11y
import dev.sdui.kmp.protocol.A11yRole
import dev.sdui.kmp.protocol.LiveRegion

/**
 * Projects protocol [A11y] onto a Compose [Modifier.semantics] block so widget renderers
 * stay a11y-aware without repeating the plumbing.
 *
 * Usage from a renderer:
 * ```
 * Text(text = content, modifier = modifier.applyA11y(node.a11y, store))
 * ```
 *
 * Fields without server-provided values are left untouched — Compose's own heuristics still
 * supply sensible defaults (e.g. Text's own content is its accessibility label). The helper
 * only *adds* semantic info the server explicitly wants surfaced.
 */
public fun Modifier.applyA11y(a11y: A11y?, store: StateStore): Modifier {
    if (a11y == null) return this
    return this.semantics(mergeDescendants = false) {
        a11y.label?.resolve(store)?.let { contentDescription = it }
        a11y.hint?.resolve(store)?.let { stateDescription = it }
        a11y.role?.let { applyRole(it) }
        if (a11y.headingLevel != null) heading()
        if (a11y.isHidden) invisibleToUser()
        when (a11y.liveRegion) {
            LiveRegion.Off -> Unit
            LiveRegion.Polite -> liveRegion = LiveRegionMode.Polite
            LiveRegion.Assertive -> liveRegion = LiveRegionMode.Assertive
        }
    }
}

private fun SemanticsPropertyReceiver.applyRole(role: A11yRole) {
    // Compose's Role enum is narrower than our protocol's A11yRole. Roles without a direct
    // Compose mapping (Link, Header, List, ListItem, Slider, TextField) are intentionally
    // left to the widget renderer's own default semantics.
    when (role) {
        A11yRole.Button -> this.role = Role.Button
        A11yRole.Checkbox -> this.role = Role.Checkbox
        A11yRole.Radio -> this.role = Role.RadioButton
        A11yRole.Switch -> this.role = Role.Switch
        A11yRole.Image -> this.role = Role.Image
        A11yRole.Link,
        A11yRole.Header,
        A11yRole.List,
        A11yRole.ListItem,
        A11yRole.Slider,
        A11yRole.TextField,
        -> Unit
    }
}
