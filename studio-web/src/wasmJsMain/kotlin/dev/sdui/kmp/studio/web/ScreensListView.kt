package dev.sdui.kmp.studio.web

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.ScreenSummary
import dev.sdui.kmp.studio.web.api.StudioApi

/**
 * Lists screens fetched from `GET /admin/screens`. Clicking a row hands the screen id back
 * to [onOpen] so [MainShell] can switch to the detail view.
 *
 * If [draftsOnly] is true, the list is filtered client-side to screens with `hasDraft = true`,
 * which powers the "Drafts" navigation item without requiring a separate server endpoint.
 *
 * Loading and error states are flat (centred message + retry); a pretty empty-state
 * illustration and inline filtering by name land with S5.
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
        ScreensState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is ScreensState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not load screens: ${s.message}")
                TextButton(onClick = { refreshTick += 1 }) { Text("Retry") }
            }
        }
        is ScreensState.Ready -> {
            val rows = if (draftsOnly) s.screens.filter { it.hasDraft } else s.screens
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (draftsOnly) {
                            "No drafts in progress. Open a screen and click \"Save draft\" to start."
                        } else {
                            "No screens yet. Create one with the studio-server CLI."
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = rows, key = { it.id }) { row ->
                        ScreenSummaryCard(row = row, onOpen = onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenSummaryCard(row: ScreenSummary, onOpen: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(row.id) }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.id)
                if (row.hasDraft) {
                    AssistChip(
                        onClick = { onOpen(row.id) },
                        label = { Text("draft") },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                val versionLabel = row.currentVersion?.let { "v$it" } ?: "unpublished"
                Text(text = versionLabel)
                Text(
                    text = "  •  updated: ${row.updatedAt}",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private sealed interface ScreensState {
    data object Loading : ScreensState
    data class Ready(val screens: List<ScreenSummary>) : ScreensState
    data class Error(val message: String) : ScreensState
}
