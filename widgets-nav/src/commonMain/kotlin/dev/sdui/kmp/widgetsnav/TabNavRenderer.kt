package dev.sdui.kmp.widgetsnav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NavHost

/**
 * Material 3 [Scaffold] + [NavigationBar] presentation of [NavHost] when its
 * [dev.sdui.kmp.protocol.NavKind] is `Tab`.
 *
 * Tabs are derived from `node.routes.keys` in declaration order — `LinkedHashMap` semantics
 * are preserved by `kotlinx.serialization` for `Map<String, String>`, so the same JSON always
 * yields the same tab order.
 *
 * The selected tab id is held in a [rememberSaveable] state so configuration-change /
 * process-restart restoration keeps the user on the same tab. The body slot mounts a fresh
 * [NestedSduiHost] for the active tab; switching tabs disposes the previous nested host's
 * effects (by Composable identity) and starts a new one — by design, so each tab owns its
 * own state. A future enhancement could keep tabs alive in the background via
 * `MovableContent` if state preservation across tab switches becomes a requirement.
 */
@Composable
@Suppress("FunctionNaming") // Compose composables are PascalCase by convention.
internal fun TabNavRenderer(node: NavHost, modifier: Modifier) {
    val tabIds = node.routes.keys.toList()
    if (tabIds.isEmpty()) {
        EmptyNavPlaceholder(modifier = modifier, message = "NavHost(kind=Tab) has no routes")
        return
    }
    var currentTabId by rememberSaveable(node.id.value) { mutableStateOf(initialTabId(node, tabIds)) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabIds.forEach { tabId ->
                    NavigationBarItem(
                        selected = tabId == currentTabId,
                        onClick = { currentTabId = tabId },
                        // Stub icon: protocol carries no per-tab icon today. We draw a small
                        // colored disk so the bar reads as a real navigation bar without
                        // pulling in compose-material-icons. When NavHost gains a per-route
                        // icon token we render that here instead.
                        icon = {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        },
                        label = { Text(tabId) },
                    )
                }
            }
        },
    ) { padding ->
        val route = node.routes[currentTabId]
        if (route == null) {
            // Defensive: declaration order guarantees tabIds[i] is in routes, but guard the
            // theoretical case where someone hand-constructs an inconsistent NavHost.
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyNavPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    message = "Route for tab '$currentTabId' is missing.",
                )
            }
        } else {
            NestedSduiHost(route = route, modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

/**
 * Resolves [NavHost.initial] to a tab id.
 *
 * Precedence:
 *  1. [Destination.TabSwitch] explicitly names the tab.
 *  2. [Destination.ScreenDest] / [Destination.Modal] — match by route value if any tab points
 *     at that route.
 *  3. Fallback: first declared tab in [NavHost.routes].
 */
internal fun initialTabId(node: NavHost, tabIds: List<String>): String {
    return when (val initial = node.initial) {
        is Destination.TabSwitch -> if (initial.tabId in tabIds) initial.tabId else tabIds.first()
        is Destination.ScreenDest -> tabIds.firstOrNull { node.routes[it] == initial.route } ?: tabIds.first()
        is Destination.Modal -> tabIds.firstOrNull { node.routes[it] == initial.route } ?: tabIds.first()
        is Destination.Back, Destination.PopToRoot -> tabIds.first()
        // A destination added by a newer server that this client cannot decode falls back to the
        // first declared tab rather than throwing.
        is Destination.Unknown -> tabIds.first()
    }
}
