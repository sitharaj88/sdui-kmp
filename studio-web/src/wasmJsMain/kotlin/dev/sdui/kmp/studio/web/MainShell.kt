package dev.sdui.kmp.studio.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.components.ChipKind
import dev.sdui.kmp.studio.web.components.StatusChip
import dev.sdui.kmp.studio.web.state.AuthState
import dev.sdui.kmp.studio.web.theme.StudioIcons

/**
 * Authenticated Studio shell. Material3 [Scaffold] with a compact branded top bar
 * (user-menu dropdown + "Sign out") and a left-side [NavigationRail] selecting between
 * Screens, Drafts, Audit, and Experiments.
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

    val hairline = MaterialTheme.colorScheme.outlineVariant
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.drawBehind {
                    drawLine(
                        color = hairline,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = Stroke.HairlineWidth.coerceAtLeast(1f),
                    )
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StudioLogoMark(size = TOP_BAR_LOGO_SIZE)
                        Text(
                            text = "sdui-kmp Studio",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                },
                actions = {
                    val email = authState.email
                    val role = authState.role
                    Box {
                        TextButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = StudioIcons.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(ACCOUNT_ICON_SIZE),
                            )
                            Text(
                                text = email ?: "account",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            if (role != null) {
                                StatusChip(text = role, kind = ChipKind.Neutral)
                            }
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = email ?: "(unknown email)",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        if (role != null) {
                                            Text(
                                                text = "role: $role",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                    }
                                },
                                enabled = false,
                                onClick = {},
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = { Text(if (signingOut) "Signing out…" else "Sign out") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = StudioIcons.Logout,
                                        contentDescription = null,
                                        modifier = Modifier.size(MENU_ICON_SIZE),
                                    )
                                },
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
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxHeight(),
            ) {
                // Icons are hand-built ImageVectors in StudioIcons — the "lightweight inline
                // SVGs" plan of record. `compose-material-icons` stays off the classpath to
                // keep the wasm bundle small (see :widgets-nav/TabNavRenderer.kt).
                StudioTab.entries.forEach { entry ->
                    NavigationRailItem(
                        selected = tab == entry,
                        onClick = {
                            tab = entry
                            selectedScreenId = null
                        },
                        icon = {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = entry.label,
                                modifier = Modifier.size(RAIL_ICON_SIZE),
                            )
                        },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationRailItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            VerticalDivider(color = hairline)

            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
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
            TextButton(onClick = onClearSelection) {
                Icon(
                    imageVector = StudioIcons.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(BACK_ICON_SIZE),
                )
                Text(
                    text = if (draftsOnly) "  Back to drafts" else "  Back to screens",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            ScreenDetailView(api = api, authState = authState, screenId = selectedScreenId)
        }
    }
}

/** Top-level Studio tabs. Add new entries before the closing brace; ordering drives the rail. */
private enum class StudioTab(val label: String, val icon: ImageVector) {
    Screens("Screens", StudioIcons.Screens),
    Drafts("Drafts", StudioIcons.Drafts),
    Audit("Audit", StudioIcons.Audit),
    Experiments("Experiments", StudioIcons.Experiments),
}

private val TOP_BAR_LOGO_SIZE = 22.dp
private val ACCOUNT_ICON_SIZE = 16.dp
private val MENU_ICON_SIZE = 16.dp
private val RAIL_ICON_SIZE = 20.dp
private val BACK_ICON_SIZE = 16.dp
