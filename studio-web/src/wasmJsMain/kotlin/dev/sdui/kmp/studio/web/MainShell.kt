package dev.sdui.kmp.studio.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.state.AuthState

/**
 * Authenticated Studio shell. Material3 [Scaffold] with a top bar (user-menu dropdown +
 * "Sign out") and a left-side [NavigationRail] selecting between Screens, Drafts, and Audit.
 *
 * The "Drafts" tab reuses [ScreensListView] with `draftsOnly = true`; it's a power-user
 * shortcut so editors don't have to remember which screens they had open.
 *
 * Sign-out posts to `/admin/auth/logout` to revoke the session row server-side, then clears
 * [AuthState] (which also wipes the cached token from `localStorage`). The [App] composable
 * recomposes back to the login screen automatically.
 *
 * The shell also owns the "currently selected screen" state. Clicking a row in the screens or
 * drafts list sets it; [ScreenDetailView] reads it. We keep this here (rather than a global
 * state holder) because it's scoped to the shell's lifetime — signing out drops the entire
 * shell tree, which is exactly when we want the selection to drop too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
public fun MainShell(api: StudioApi, authState: AuthState) {
    var tab by remember { mutableStateOf(StudioTab.Screens) }
    var selectedScreenId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }

    LaunchedEffect(signingOut) {
        if (!signingOut) return@LaunchedEffect
        // Best-effort revoke; if the server is unreachable we still clear local state to get
        // the operator out of an unauthenticated session as fast as possible.
        runCatching { api.logout() }
        selectedScreenId = null
        authState.signOut()
        signingOut = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("sdui-kmp Studio") },
                actions = {
                    val email = authState.email
                    val role = authState.role
                    Box {
                        TextButton(onClick = { menuOpen = true }) {
                            Text(text = email ?: "account")
                            if (role != null) {
                                Text(text = " ($role)", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(text = email ?: "(unknown email)")
                                        if (role != null) {
                                            Text(
                                                text = "role: $role",
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                    }
                                },
                                enabled = false,
                                onClick = {},
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (signingOut) "Signing out…" else "Sign out") },
                                enabled = !signingOut,
                                onClick = {
                                    menuOpen = false
                                    signingOut = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                // Iconography is intentionally text-only — `compose-material-icons` would
                // balloon the wasm bundle, and the rest of the framework deliberately avoids
                // it (see :widgets-nav/TabNavRenderer.kt). Labels carry the meaning; S5 swaps
                // in lightweight inline SVGs when the visual editor lands.
                NavigationRailItem(
                    selected = tab == StudioTab.Screens,
                    onClick = {
                        tab = StudioTab.Screens
                        selectedScreenId = null
                    },
                    icon = { Text("S") },
                    label = { Text("Screens") },
                )
                NavigationRailItem(
                    selected = tab == StudioTab.Drafts,
                    onClick = {
                        tab = StudioTab.Drafts
                        selectedScreenId = null
                    },
                    icon = { Text("D") },
                    label = { Text("Drafts") },
                )
                NavigationRailItem(
                    selected = tab == StudioTab.Audit,
                    onClick = {
                        tab = StudioTab.Audit
                        selectedScreenId = null
                    },
                    icon = { Text("A") },
                    label = { Text("Audit") },
                )
                // M-S6: experiments tab.
                NavigationRailItem(
                    selected = tab == StudioTab.Experiments,
                    onClick = {
                        tab = StudioTab.Experiments
                        selectedScreenId = null
                    },
                    icon = { Text("X") },
                    label = { Text("Experiments") },
                )
            }

            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (tab) {
                    StudioTab.Screens -> ScreensTabContent(
                        api = api,
                        authState = authState,
                        selectedScreenId = selectedScreenId,
                        onSelectScreen = { selectedScreenId = it },
                        onClearSelection = { selectedScreenId = null },
                        draftsOnly = false,
                    )
                    StudioTab.Drafts -> ScreensTabContent(
                        api = api,
                        authState = authState,
                        selectedScreenId = selectedScreenId,
                        onSelectScreen = { selectedScreenId = it },
                        onClearSelection = { selectedScreenId = null },
                        draftsOnly = true,
                    )
                    StudioTab.Audit -> AuditView(api = api)
                    StudioTab.Experiments -> ExperimentsView(api = api)
                }
            }
        }
    }
}

/**
 * Splits the screens / drafts tab between the list and the detail view. Wrapping this in its
 * own composable keeps [MainShell] focused on chrome and tab routing.
 */
@Composable
private fun ScreensTabContent(
    api: StudioApi,
    authState: AuthState,
    selectedScreenId: String?,
    onSelectScreen: (String) -> Unit,
    onClearSelection: () -> Unit,
    draftsOnly: Boolean,
) {
    if (selectedScreenId == null) {
        ScreensListView(api = api, onOpen = onSelectScreen, draftsOnly = draftsOnly)
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(onClick = onClearSelection) { Text("← Back to list") }
            ScreenDetailView(api = api, authState = authState, screenId = selectedScreenId)
        }
    }
}

/** Top-level Studio tabs. Add new entries before the closing brace; ordering drives the rail. */
private enum class StudioTab { Screens, Drafts, Audit, Experiments }
