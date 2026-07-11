package dev.sdui.kmp.widgetsnav

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NavHost
import dev.sdui.kmp.runtime.LocalActionDispatcher
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.applyA11y
import dev.sdui.kmp.runtime.resolve
import kotlinx.coroutines.launch

/**
 * Material 3 [ModalBottomSheet] presentation of [NavHost] when its
 * [dev.sdui.kmp.protocol.NavKind] is `BottomSheet`.
 *
 * The sheet is always-visible while the surrounding screen tree contains the NavHost. The
 * route at [NavHost.initial] (or the first declared route) is rendered as nested content.
 *
 * Dismissal contract: a real bottom-sheet body should ship a "Cancel" / "Close" button whose
 * [Action] is `Action.Navigate(Destination.Back())`. The host-level [dev.sdui.kmp.runtime.Navigator]
 * already handles back, which causes the parent screen to re-fetch without the NavHost.
 *
 * Swipe-to-dismiss fires the dispatcher with the same `Back` action so users get a native
 * gesture path. Hosts that wire `LocalActionDispatcher` to a no-op composition (tests,
 * previews) gracefully fall through with [onDismissRequest] effectively a no-op.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming") // Compose composables are PascalCase by convention.
internal fun BottomSheetNavRenderer(node: NavHost, modifier: Modifier) {
    val initialRoute = (node.initial as? Destination.ScreenDest)?.route
        ?: (node.initial as? Destination.Modal)?.route
        ?: node.routes.values.firstOrNull()
    if (initialRoute == null) {
        EmptyNavPlaceholder(
            modifier = modifier,
            message = "NavHost(kind=BottomSheet) has no routes",
        )
        return
    }
    val sheetState = rememberModalBottomSheetState()
    val dispatcher = LocalActionDispatcher.current
    val coroutineScope = rememberCoroutineScope()
    val store = LocalStateStore.current
    // Compose's `paneTitle` is the WCAG 4.1.2 (Name, Role, Value) recommended way to label
    // a modal pane: screen readers announce the title when the sheet opens. We project the
    // server-supplied `node.a11y.label` into the title so the same metadata that powers
    // descendant semantics also names the sheet itself. Falls back to the route name when
    // the server didn't supply a label — bare-route announcements are better than silence.
    val resolvedTitle: String = node.a11y?.label?.resolve(store) ?: initialRoute
    ModalBottomSheet(
        onDismissRequest = {
            // Translate sheet swipe-down into a server-driven Back navigation. The host
            // navigator pops the modal route, which removes the NavHost from the next
            // server response.
            coroutineScope.launch { dispatcher.dispatch(Action.Navigate(Destination.Back())) }
        },
        sheetState = sheetState,
    ) {
        NestedSduiHost(
            route = initialRoute,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { paneTitle = resolvedTitle }
                .applyA11y(node.a11y, store),
        )
    }
}
