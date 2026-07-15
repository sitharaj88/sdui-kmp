package dev.sdui.kmp.studio.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.ScreenSummary
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.components.ChipKind
import dev.sdui.kmp.studio.web.components.EmptyState
import dev.sdui.kmp.studio.web.components.ErrorState
import dev.sdui.kmp.studio.web.components.LoadingState
import dev.sdui.kmp.studio.web.components.StatusChip
import dev.sdui.kmp.studio.web.theme.StudioIcons
import dev.sdui.kmp.studio.web.theme.studioColors

/**
 * Lists screens fetched from `GET /admin/screens`. Clicking a row hands the screen id back
 * to [onOpen] so [MainShell] can switch to the detail view.
 *
 * If [draftsOnly] is true, the list is filtered client-side to screens with `hasDraft = true`,
 * which powers the "Drafts" navigation item without requiring a separate server endpoint.
 */
@Composable
public fun ScreensListView(
    api: StudioApi,
    onOpen: (String) -> Unit,
    draftsOnly: Boolean = false,
) {
    var state by remember { mutableStateOf<ScreensState>(ScreensState.Loading) }
    // Counter-style refresh trigger — same pattern as `LoginScreen.submitTick`. Bumping it
    // re-fires the LaunchedEffect that does the fetch, which is enough for the skeleton.
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        state = ScreensState.Loading
        state = try {
            ScreensState.Ready(api.listScreens())
        } catch (t: Throwable) {
            ScreensState.Error(message = t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    when (val s = state) {
        ScreensState.Loading -> LoadingState(label = "Loading screens…")
        is ScreensState.Error -> ErrorState(
            message = "Could not load screens: ${s.message}",
            onRetry = { refreshTick += 1 },
        )
        is ScreensState.Ready -> {
            val rows = if (draftsOnly) s.screens.filter { it.hasDraft } else s.screens
            if (rows.isEmpty()) {
                if (draftsOnly) {
                    EmptyState(
                        title = "No drafts in progress",
                        hint = "Open a screen and click \"Save draft\" to start.",
                        icon = StudioIcons.Drafts,
                    )
                } else {
                    EmptyState(
                        title = "No screens yet",
                        hint = "Create one with the studio-server CLI.",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = rows, key = { it.id }) { row ->
                        ScreenSummaryRow(row = row, onOpen = onOpen)
                    }
                }
            }
        }
    }
}

/**
 * One dense list row: monospace screen id, draft/version chips, updated timestamp, chevron.
 * Hover tints the surface and shows a hand cursor — list rows are the primary navigation
 * surface of the whole Studio, so they should feel clickable.
 */
@Composable
private fun ScreenSummaryRow(row: ScreenSummary, onOpen: (String) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val restingColor = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (hovered) studioColors.hoverOverlay.compositeOver(restingColor) else restingColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onOpen(row.id) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(min = ROW_MIN_HEIGHT).padding(horizontal = 12.dp),
        ) {
            Text(
                text = row.id,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            if (row.hasDraft) {
                StatusChip(text = "draft", kind = ChipKind.Warning)
            }
            Spacer(Modifier.weight(1f))
            val versionLabel = row.currentVersion?.let { "v$it" }
            if (versionLabel != null) {
                StatusChip(text = versionLabel, kind = ChipKind.Neutral)
            } else {
                Text(
                    text = "unpublished",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = row.updatedAt,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = StudioIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CHEVRON_SIZE),
            )
        }
    }
}

private sealed interface ScreensState {
    data object Loading : ScreensState
    data class Ready(val screens: List<ScreenSummary>) : ScreensState
    data class Error(val message: String) : ScreensState
}

private val ROW_MIN_HEIGHT = 44.dp
private val CHEVRON_SIZE = 16.dp
