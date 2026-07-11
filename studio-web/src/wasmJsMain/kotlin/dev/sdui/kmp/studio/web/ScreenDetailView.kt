package dev.sdui.kmp.studio.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.runtime.NativeSurfaces
import dev.sdui.kmp.runtime.SduiHost
import dev.sdui.kmp.runtime.WidgetRegistry
import dev.sdui.kmp.runtime.rememberNavigator
import dev.sdui.kmp.runtime.staticScreenSource
import dev.sdui.kmp.studio.web.api.DraftSaveResult
import dev.sdui.kmp.studio.web.api.PublishResult
import dev.sdui.kmp.studio.web.api.ScreenVersion
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.editor.VisualEditor
import dev.sdui.kmp.studio.web.state.AuthState
import dev.sdui.kmp.widgetscore.WidgetsCore
import dev.sdui.kmp.widgetsforms.WidgetsForms
import dev.sdui.kmp.widgetsmedia.WidgetsMedia
import dev.sdui.kmp.widgetsnav.WidgetsNav
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Two-pane editor view: editable JSON on the left, live preview on the right; plus a History
 * tab listing the screen's published versions.
 *
 * The Editor tab loads the calling editor's draft (if any) or otherwise the current published
 * version, and lets them edit the raw JSON. Three buttons:
 *  * "Save draft" — `PUT /admin/screens/{id}/draft`. Server-validates against
 *    `Screen.serializer()`; violations land inline.
 *  * "Publish" — `POST /admin/screens/{id}/publish`. Disabled while there are unsaved edits.
 *  * "Discard changes" — re-loads the body from the server, dropping local edits.
 *
 * The preview pane re-renders on every keystroke that produces a structurally-valid `Screen`.
 * Decode errors surface inline (in red) without touching the preview, so the editor sees the
 * last successful render while they fix the JSON.
 *
 * No submit handler is wired into the preview — `Action.Submit` triggered from rendered widgets
 * is a no-op here. That is the correct behaviour: clicking "Like" inside the preview must not
 * fire a request against the production server. A recording mock submit handler is S5 work.
 *
 * History tab is read-only by default; admins additionally see a "Revert" button per row.
 */
@Composable
public fun ScreenDetailView(
    api: StudioApi,
    authState: AuthState,
    screenId: String,
) {
    var tab by remember(screenId) { mutableStateOf(DetailTab.Editor) }
    var refreshTick by remember(screenId) { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(
                selected = tab == DetailTab.Editor,
                onClick = { tab = DetailTab.Editor },
                text = { Text("Editor") },
            )
            Tab(
                selected = tab == DetailTab.History,
                onClick = { tab = DetailTab.History },
                text = { Text("History") },
            )
        }
        Box(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            when (tab) {
                DetailTab.Editor -> EditorTab(
                    api = api,
                    screenId = screenId,
                    refreshKey = refreshTick,
                    onPublishedOrReverted = { refreshTick += 1 },
                )
                DetailTab.History -> HistoryTab(
                    api = api,
                    authState = authState,
                    screenId = screenId,
                    refreshKey = refreshTick,
                    onReverted = { refreshTick += 1 },
                )
            }
        }
    }
}

private enum class DetailTab { Editor, History }

@Composable
@Suppress("LongMethod")
private fun EditorTab(
    api: StudioApi,
    screenId: String,
    refreshKey: Int,
    onPublishedOrReverted: () -> Unit,
) {
    var initialBody by remember(screenId, refreshKey) { mutableStateOf<String?>(null) }
    var loadingMessage by remember(screenId, refreshKey) { mutableStateOf<String?>(null) }
    var loadError by remember(screenId, refreshKey) { mutableStateOf<String?>(null) }
    var hasDraft by remember(screenId, refreshKey) { mutableStateOf(false) }

    // Initial load: prefer draft over published. The server gives us 404 on draft when none
    // exists, which is normal — we then fall through to the published body.
    LaunchedEffect(screenId, refreshKey) {
        loadingMessage = "Loading…"
        loadError = null
        try {
            val draft = api.getDraft(screenId)
            if (draft != null) {
                initialBody = draft.body
                hasDraft = true
            } else {
                val screen = api.getScreen(screenId)
                initialBody = screen.body
                hasDraft = false
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t::class.simpleName ?: "unknown error"
        } finally {
            loadingMessage = null
        }
    }

    val body = initialBody
    when {
        loadingMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Could not load screen $screenId: $loadError")
        }
        body != null -> EditorPanes(
            api = api,
            screenId = screenId,
            initialBody = body,
            initiallyHasDraft = hasDraft,
            onPublishedOrReverted = onPublishedOrReverted,
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
@Suppress("LongMethod", "ComplexMethod")
private fun EditorPanes(
    api: StudioApi,
    screenId: String,
    initialBody: String,
    initiallyHasDraft: Boolean,
    onPublishedOrReverted: () -> Unit,
) {
    var text by remember(screenId, initialBody) { mutableStateOf(initialBody) }
    var lastSavedText by remember(screenId, initialBody) { mutableStateOf(initialBody) }
    var hasDraft by remember(screenId, initialBody) { mutableStateOf(initiallyHasDraft) }
    var decodeError by remember(screenId) { mutableStateOf<String?>(null) }
    var lastValidScreen by remember(screenId) { mutableStateOf<Screen?>(null) }
    var saveStatus by remember(screenId) { mutableStateOf<SaveStatus>(SaveStatus.Idle) }
    var publishStatus by remember(screenId) { mutableStateOf<PublishStatus>(PublishStatus.Idle) }
    var editorMode by remember(screenId) { mutableStateOf(EditorMode.Json) }

    val unsaved = text != lastSavedText

    // Debounced live decode. We re-decode every change but only after a short idle period to
    // avoid hammering the protocol decoder on every keystroke. The preview pane reads
    // [lastValidScreen]; while the JSON is broken the preview keeps rendering the previous
    // valid tree, exactly like Monaco's preview-on-error mode.
    LaunchedEffect(screenId) {
        snapshotFlow { text }
            .debounce(timeoutMillis = LIVE_DECODE_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collectLatest { current ->
                runCatching {
                    SduiJson.decodeFromString(Screen.serializer(), current)
                }.onSuccess {
                    lastValidScreen = it
                    decodeError = null
                }.onFailure {
                    decodeError = it.message ?: it::class.simpleName ?: "decode failed"
                }
            }
    }

    // Trigger save / publish via tick counters so we can reuse the LaunchedEffect pattern.
    var saveTick by remember(screenId) { mutableStateOf(0) }
    var publishTick by remember(screenId) { mutableStateOf(0) }

    LaunchedEffect(saveTick) {
        if (saveTick == 0) return@LaunchedEffect
        saveStatus = SaveStatus.Saving
        val outcome = runCatching { api.putDraft(screenId, text) }
        val result = outcome.getOrNull()
        if (result == null) {
            val err = outcome.exceptionOrNull()
            saveStatus = SaveStatus.Error(err?.message ?: err?.let { it::class.simpleName } ?: "network error")
            return@LaunchedEffect
        }
        when (result) {
            is DraftSaveResult.Saved -> {
                lastSavedText = result.draft.body
                text = result.draft.body
                hasDraft = true
                saveStatus = SaveStatus.Saved(result.draft.updatedAt)
            }
            is DraftSaveResult.Invalid -> saveStatus = SaveStatus.ValidationFailed(result.violations)
            is DraftSaveResult.Failure -> saveStatus = SaveStatus.Error("HTTP ${result.statusCode}")
        }
    }

    LaunchedEffect(publishTick) {
        if (publishTick == 0) return@LaunchedEffect
        publishStatus = PublishStatus.Publishing
        val outcome = runCatching { api.publish(screenId) }
        val result = outcome.getOrNull()
        if (result == null) {
            val err = outcome.exceptionOrNull()
            publishStatus = PublishStatus.Error(
                err?.message ?: err?.let { it::class.simpleName } ?: "network error",
            )
            return@LaunchedEffect
        }
        when (result) {
            is PublishResult.Ok -> {
                publishStatus = PublishStatus.Published(result.version)
                hasDraft = false
                onPublishedOrReverted()
            }
            PublishResult.NoDraft -> publishStatus = PublishStatus.Error("No draft to publish.")
            PublishResult.Forbidden -> publishStatus = PublishStatus.Error("Forbidden.")
            is PublishResult.Failure -> publishStatus = PublishStatus.Error("HTTP ${result.statusCode}")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = screenId,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            if (hasDraft) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("draft loaded") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            Box(modifier = Modifier.weight(1f))
            Button(
                onClick = { saveTick += 1 },
                enabled = unsaved && saveStatus !is SaveStatus.Saving && decodeError == null,
            ) {
                if (saveStatus is SaveStatus.Saving) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Save draft")
            }
            OutlinedButton(
                onClick = { publishTick += 1 },
                enabled = !unsaved &&
                    hasDraft &&
                    publishStatus !is PublishStatus.Publishing &&
                    decodeError == null,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Publish")
            }
            TextButton(
                onClick = {
                    text = lastSavedText
                    saveStatus = SaveStatus.Idle
                    publishStatus = PublishStatus.Idle
                },
                enabled = unsaved,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text("Discard changes")
            }
        }

        StatusStrip(
            saveStatus = saveStatus,
            publishStatus = publishStatus,
            unsaved = unsaved,
            decodeError = decodeError,
        )

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            OutlinedButton(
                onClick = { editorMode = EditorMode.Json },
                enabled = editorMode != EditorMode.Json,
            ) { Text("JSON") }
            OutlinedButton(
                onClick = { editorMode = EditorMode.Visual },
                enabled = editorMode != EditorMode.Visual && lastValidScreen != null,
                modifier = Modifier.padding(start = 4.dp),
            ) { Text("Visual") }
        }

        when (editorMode) {
            EditorMode.Json -> Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                JsonEditorPane(
                    text = text,
                    onTextChange = { text = it },
                    decodeError = decodeError,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                PreviewPane(
                    screen = lastValidScreen,
                    hasDecodeError = decodeError != null,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            EditorMode.Visual -> {
                val visualSeed = lastValidScreen
                if (visualSeed == null) {
                    Text(
                        text = "Visual editor needs a structurally-valid Screen JSON; " +
                            "fix decode errors first.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    VisualEditor(
                        screen = visualSeed,
                        onScreenChange = { updated ->
                            text = SduiJson.encodeToString(Screen.serializer(), updated)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private enum class EditorMode { Json, Visual }

@Composable
private fun StatusStrip(
    saveStatus: SaveStatus,
    publishStatus: PublishStatus,
    unsaved: Boolean,
    decodeError: String?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (saveStatus) {
            SaveStatus.Idle -> Unit
            SaveStatus.Saving -> Text("Saving draft…")
            is SaveStatus.Saved ->
                if (!unsaved) Text("Draft saved at ${saveStatus.updatedAt}.")
            is SaveStatus.ValidationFailed -> Column {
                Text(
                    "Server rejected the draft:",
                    color = MaterialTheme.colorScheme.error,
                )
                saveStatus.violations.forEach { v ->
                    Text(text = " • $v", color = MaterialTheme.colorScheme.error)
                }
            }
            is SaveStatus.Error -> Text(
                text = "Save failed: ${saveStatus.message}",
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (publishStatus) {
            PublishStatus.Idle -> Unit
            PublishStatus.Publishing -> Text("Publishing…")
            is PublishStatus.Published -> Text("Published v${publishStatus.version}.")
            is PublishStatus.Error -> Text(
                text = "Publish failed: ${publishStatus.message}",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (decodeError != null) {
            Text(
                text = "JSON does not decode as Screen: $decodeError",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun JsonEditorPane(
    text: String,
    onTextChange: (String) -> Unit,
    decodeError: String?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = if (decodeError == null) "Editor (Screen JSON)" else "Editor (decode error — preview is stale)",
                style = MaterialTheme.typography.titleSmall,
                color = if (decodeError == null) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = false,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PreviewPane(
    screen: Screen?,
    hasDecodeError: Boolean,
    modifier: Modifier = Modifier,
) {
    // Single shared registry per recomposition is fine; the registry has no per-screen state.
    val registry = remember {
        WidgetRegistry.build {
            WidgetsCore.register(this)
            WidgetsForms.register(this)
            WidgetsMedia.register(this)
            WidgetsNav.register(this)
            NativeSurfaces.register(this)
        }
    }
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Live preview", style = MaterialTheme.typography.titleSmall)
                if (hasDecodeError) {
                    Text(
                        text = "  (showing last valid render)",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                if (screen == null) {
                    Text("Type valid Screen JSON on the left to see a live preview.")
                } else {
                    RenderScreen(screen = screen, registry = registry)
                }
            }
        }
    }
}

/**
 * Wraps the decoded [Screen] in a [staticScreenSource] and hands it to [SduiHost].
 *
 * No submit handler, no live transport, no nested-screen factory — the preview is a pure
 * static render. Anything navigation-shaped (Action.Navigate routing inside a NavHost widget)
 * still works because [rememberNavigator] is in-process; cross-screen navigation that would
 * trigger a fresh fetch from the server is out of scope here and silently no-ops.
 */
@Composable
private fun RenderScreen(screen: Screen, registry: WidgetRegistry) {
    val navigator = rememberNavigator(initial = "/preview")
    val source = remember(screen) { staticScreenSource(screen) }
    SduiHost(
        source = source,
        registry = registry,
        navigator = navigator,
    )
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun HistoryTab(
    api: StudioApi,
    authState: AuthState,
    screenId: String,
    refreshKey: Int,
    onReverted: () -> Unit,
) {
    var state by remember(screenId, refreshKey) {
        mutableStateOf<HistoryState>(HistoryState.Loading)
    }
    var revertTarget by remember(screenId) { mutableStateOf<Int?>(null) }
    var revertInFlight by remember(screenId) { mutableStateOf(false) }
    var revertError by remember(screenId) { mutableStateOf<String?>(null) }

    LaunchedEffect(screenId, refreshKey) {
        state = HistoryState.Loading
        state = try {
            HistoryState.Ready(api.listVersions(screenId))
        } catch (t: Throwable) {
            HistoryState.Error(t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    val isAdmin = authState.role == "admin"

    Column(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            HistoryState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is HistoryState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("Could not load history: ${s.message}") }
            is HistoryState.Ready -> if (s.versions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No published versions yet.")
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    Text(
                        "Per-version JSON preview requires a server endpoint that doesn't yet " +
                            "exist (GET /admin/screens/{id}/versions/{n}). Listed here for " +
                            "now; preview lands once the server route is added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    s.versions.forEach { version ->
                        VersionRow(
                            version = version,
                            isAdmin = isAdmin,
                            onRevertClick = { revertTarget = version.version },
                        )
                    }
                }
            }
        }
    }

    revertTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!revertInFlight) revertTarget = null },
            title = { Text("Revert to v$target?") },
            text = {
                Column {
                    Text(
                        "Reverting will create a new published version that copies the body from " +
                            "v$target. The current head is not deleted.",
                    )
                    revertError?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !revertInFlight,
                    onClick = {
                        revertInFlight = true
                        revertError = null
                    },
                ) { Text(if (revertInFlight) "Reverting…" else "Revert") }
            },
            dismissButton = {
                TextButton(onClick = { if (!revertInFlight) revertTarget = null }) { Text("Cancel") }
            },
        )
        LaunchedEffect(revertInFlight, target) {
            if (!revertInFlight) return@LaunchedEffect
            val outcome = runCatching { api.revert(screenId, target) }
            val result = outcome.getOrNull()
            if (result == null) {
                val err = outcome.exceptionOrNull()
                revertError = err?.message ?: err?.let { it::class.simpleName } ?: "network error"
                revertInFlight = false
                return@LaunchedEffect
            }
            when (result) {
                is PublishResult.Ok -> {
                    revertInFlight = false
                    revertTarget = null
                    onReverted()
                }
                PublishResult.Forbidden -> {
                    revertError = "Forbidden — admin role required."
                    revertInFlight = false
                }
                PublishResult.NoDraft -> {
                    revertError = "Source version not found."
                    revertInFlight = false
                }
                is PublishResult.Failure -> {
                    revertError = "HTTP ${result.statusCode}"
                    revertInFlight = false
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: ScreenVersion,
    isAdmin: Boolean,
    onRevertClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "v${version.version}", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "published: ${version.publishedAt ?: "—"}  •  by editor ${version.editorId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isAdmin) {
                OutlinedButton(onClick = onRevertClick) { Text("Revert") }
            }
        }
    }
}

private sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus
    data class Saved(val updatedAt: String) : SaveStatus
    data class ValidationFailed(val violations: List<String>) : SaveStatus
    data class Error(val message: String) : SaveStatus
}

private sealed interface PublishStatus {
    data object Idle : PublishStatus
    data object Publishing : PublishStatus
    data class Published(val version: Int) : PublishStatus
    data class Error(val message: String) : PublishStatus
}

private sealed interface HistoryState {
    data object Loading : HistoryState
    data class Ready(val versions: List<ScreenVersion>) : HistoryState
    data class Error(val message: String) : HistoryState
}

private const val LIVE_DECODE_DEBOUNCE_MS: Long = 200L
