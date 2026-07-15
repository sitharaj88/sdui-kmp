package dev.sdui.kmp.studio.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.AuditEntry
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.components.ChipKind
import dev.sdui.kmp.studio.web.components.EmptyState
import dev.sdui.kmp.studio.web.components.ErrorState
import dev.sdui.kmp.studio.web.components.LoadingState
import dev.sdui.kmp.studio.web.components.StatusChip
import dev.sdui.kmp.studio.web.components.StudioPanel
import dev.sdui.kmp.studio.web.components.ToolbarButton
import dev.sdui.kmp.studio.web.components.ToolbarOutlinedButton
import dev.sdui.kmp.studio.web.components.ToolbarTextButton
import dev.sdui.kmp.studio.web.components.studioFieldColors
import dev.sdui.kmp.studio.web.export.downloadCsv
import dev.sdui.kmp.studio.web.theme.StudioIcons

/**
 * Filtered audit-log viewer.
 *
 * Server-side filters: `screenId` exact match, `editorId` UUID, `from` / `to` ISO-8601
 * timestamps. We expose them as plain text fields rather than a Material3 `DatePicker` because
 * the picker's wasm rendering is awkward in this Compose Multiplatform release; a minimalist
 * `YYYY-MM-DD` text field round-trips into `Instant.parse` cleanly with a `T00:00:00Z` suffix
 * appended client-side.
 *
 * "Apply filters" issues a fresh `GET /admin/audit?...` and replaces the visible list.
 * "Export CSV" downloads the *currently visible* (post-filter) list as a CSV file via
 * `URL.createObjectURL(Blob(...))` + a hidden anchor element — see [downloadCsv].
 */
@Composable
@Suppress("LongMethod")
public fun AuditView(api: StudioApi) {
    var screenId by remember { mutableStateOf("") }
    var editorId by remember { mutableStateOf("") }
    var fromDate by remember { mutableStateOf("") }
    var toDate by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var loadTick by remember { mutableStateOf(0) }

    LaunchedEffect(loadTick) {
        loading = true
        errorText = null
        try {
            entries = api.listAudit(
                screenId = screenId.trim().ifBlank { null },
                editorId = editorId.trim().ifBlank { null },
                from = fromDate.toIsoOrNull(endOfDay = false),
                to = toDate.toIsoOrNull(endOfDay = true),
            )
        } catch (t: Throwable) {
            errorText = t.message ?: t::class.simpleName ?: "unknown error"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FiltersPanel(
            screenId = screenId,
            onScreenIdChange = { screenId = it },
            editorId = editorId,
            onEditorIdChange = { editorId = it },
            fromDate = fromDate,
            onFromDateChange = { fromDate = it },
            toDate = toDate,
            onToDateChange = { toDate = it },
            onApply = { loadTick += 1 },
            onClear = {
                screenId = ""
                editorId = ""
                fromDate = ""
                toDate = ""
                loadTick += 1
            },
            onExport = { downloadCsv(fileName = csvFileName(screenId), entries = entries) },
            entryCount = entries.size,
        )
        when {
            loading -> LoadingState(label = "Loading audit log…")
            errorText != null -> ErrorState(
                message = "Could not load audit log: $errorText",
                onRetry = { loadTick += 1 },
            )
            entries.isEmpty() -> EmptyState(
                title = "No audit entries",
                hint = "Nothing matches the current filters.",
                icon = StudioIcons.Audit,
            )
            else -> StudioPanel(contentPadding = 0.dp, modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = entries, key = { it.id }) { entry ->
                        AuditRow(entry = entry)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun FiltersPanel(
    screenId: String,
    onScreenIdChange: (String) -> Unit,
    editorId: String,
    onEditorIdChange: (String) -> Unit,
    fromDate: String,
    onFromDateChange: (String) -> Unit,
    toDate: String,
    onToDateChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    entryCount: Int,
) {
    StudioPanel(title = "FILTERS", modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = screenId,
                    onValueChange = onScreenIdChange,
                    label = { Text("Screen ID") },
                    singleLine = true,
                    colors = studioFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = editorId,
                    onValueChange = onEditorIdChange,
                    label = { Text("Editor UUID") },
                    singleLine = true,
                    colors = studioFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fromDate,
                    onValueChange = onFromDateChange,
                    label = { Text("From (YYYY-MM-DD)") },
                    singleLine = true,
                    colors = studioFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = toDate,
                    onValueChange = onToDateChange,
                    label = { Text("To (YYYY-MM-DD)") },
                    singleLine = true,
                    colors = studioFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolbarButton(text = "Apply filters", onClick = onApply)
                ToolbarTextButton(text = "Clear", onClick = onClear)
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = "$entryCount entries",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
                ToolbarOutlinedButton(
                    text = "Export CSV",
                    icon = StudioIcons.Download,
                    onClick = onExport,
                    enabled = entryCount > 0,
                )
            }
        }
    }
}

/**
 * One divider-separated table-like row: action chip + screen id + version range on the first
 * line; timestamp, editor, and request id as monospace metadata on the second.
 */
@Composable
private fun AuditRow(entry: AuditEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(text = entry.action, kind = auditActionChipKind(entry.action))
            Text(
                text = "screen ${entry.screenId}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            val range = versionRange(entry.fromVersion, entry.toVersion)
            if (range.isNotEmpty()) {
                Text(
                    text = range.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "${entry.at}  ·  ${entry.editorId}  ·  req ${entry.requestId}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** publish → accent, revert/delete → warning/error, everything else neutral. */
private fun auditActionChipKind(action: String): ChipKind = when (action.lowercase()) {
    "publish" -> ChipKind.Accent
    "revert" -> ChipKind.Warning
    "delete" -> ChipKind.Error
    else -> ChipKind.Neutral
}

private fun versionRange(from: Int?, to: Int?): String =
    when {
        from != null && to != null -> "  (v$from → v$to)"
        to != null -> "  (v$to)"
        else -> ""
    }

private fun String.toIsoOrNull(endOfDay: Boolean): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    // Accept either bare YYYY-MM-DD or a full ISO-8601 timestamp. The server's `Instant.parse`
    // requires the latter, so we splice in T00:00:00Z / T23:59:59Z when only a date is given.
    if (DATE_REGEX.matches(trimmed)) {
        return if (endOfDay) "${trimmed}T23:59:59Z" else "${trimmed}T00:00:00Z"
    }
    return trimmed
}

private fun csvFileName(screenIdFilter: String): String {
    val suffix = if (screenIdFilter.isNotBlank()) "-${screenIdFilter.trim()}" else ""
    return "studio-audit$suffix.csv"
}

private val DATE_REGEX: Regex = Regex("""\d{4}-\d{2}-\d{2}""")
