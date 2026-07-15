package dev.sdui.kmp.studio.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.sdui.kmp.studio.web.components.ChipKind
import dev.sdui.kmp.studio.web.components.DevicePreviewFrame
import dev.sdui.kmp.studio.web.components.EmptyState
import dev.sdui.kmp.studio.web.components.ErrorState
import dev.sdui.kmp.studio.web.components.InlineStatus
import dev.sdui.kmp.studio.web.components.LoadingState
import dev.sdui.kmp.studio.web.components.SegmentOption
import dev.sdui.kmp.studio.web.components.SegmentedControl
import dev.sdui.kmp.studio.web.components.StatusChip
import dev.sdui.kmp.studio.web.components.StatusKind
import dev.sdui.kmp.studio.web.components.StudioPanel
import dev.sdui.kmp.studio.web.components.StudioTabs
import dev.sdui.kmp.studio.web.components.ToolbarButton
import dev.sdui.kmp.studio.web.components.ToolbarOutlinedButton
import dev.sdui.kmp.studio.web.components.ToolbarTextButton
import dev.sdui.kmp.studio.web.components.ValidationBanner
import dev.sdui.kmp.studio.web.editor.VisualEditor
import dev.sdui.kmp.studio.web.state.AuthState
import dev.sdui.kmp.studio.web.theme.StudioIcons
import dev.sdui.kmp.studio.web.theme.studioColors
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
 * The preview pane re-renders on every keystroke that produces a structurally-valid `Screen`,
 * inside a [DevicePreviewFrame] so tokens resolve against a stock (light by default) theme
 * rather than the Studio's dark one. Decode errors surface inline without touching the
 * preview, so the editor sees the last successful render while they fix the JSON.
 *
 * No submit handler is wired into the preview — `Action.Submit` triggered from rendered widgets
 * is a no-op here. That is the correct behaviour: clicking "Like" inside the preview must not
 * fire a request against the production server.
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
        StudioTabs(
            labels = listOf("Editor", "History"),
            selectedIndex = tab.ordinal,
            onSelect = { tab = DetailTab.entries[it] },
        )
        Box(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
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
        loadingMessage != null -> LoadingState(label = "Loading $screenId…")
        loadError != null -> ErrorState(message = "Could not load screen $screenId: $loadError")
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = screenId,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(end = 4.dp),
            )
            if (hasDraft) {
                StatusChip(text = "Draft", kind = ChipKind.Warning)
            }
            Box(modifier = Modifier.weight(1f))
            SegmentedControl(
                options = listOf(
                    SegmentOption(label = "JSON", icon = StudioIcons.Code),
                    SegmentOption(label = "Visual", icon = StudioIcons.Eye, enabled = lastValidScreen != null),
                ),
                selectedIndex = editorMode.ordinal,
                onSelect = { editorMode = EditorMode.entries[it] },
            )
            Box(modifier = Modifier.padding(start = 4.dp))
            if (saveStatus is SaveStatus.Saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(TOOLBAR_SPINNER_SIZE),
                    strokeWidth = TOOLBAR_SPINNER_STROKE,
                )
            }
            ToolbarButton(
                text = "Save draft",
                icon = StudioIcons.Save,
                onClick = { saveTick += 1 },
                enabled = unsaved && saveStatus !is SaveStatus.Saving && decodeError == null,
            )
            ToolbarOutlinedButton(
                text = "Publish",
                icon = StudioIcons.Publish,
                onClick = { publishTick += 1 },
                enabled = !unsaved &&
                    hasDraft &&
                    publishStatus !is PublishStatus.Publishing &&
                    decodeError == null,
            )
            ToolbarTextButton(
                text = "Discard",
                icon = StudioIcons.Undo,
                onClick = {
                    text = lastSavedText
                    saveStatus = SaveStatus.Idle
                    publishStatus = PublishStatus.Idle
                },
                enabled = unsaved,
            )
        }

        StatusStrip(
            saveStatus = saveStatus,
            publishStatus = publishStatus,
            unsaved = unsaved,
            decodeError = decodeError,
        )

        Box(Modifier.padding(bottom = 8.dp))

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
                    InlineStatus(
                        kind = StatusKind.Error,
                        text = "Visual editor needs a structurally-valid Screen JSON; fix decode errors first.",
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
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (saveStatus) {
            SaveStatus.Idle -> Unit
            SaveStatus.Saving -> InlineStatus(StatusKind.Accent, "Saving draft…")
            is SaveStatus.Saved ->
                if (!unsaved) InlineStatus(StatusKind.Success, "Draft saved at ${saveStatus.updatedAt}.")
            is SaveStatus.ValidationFailed -> ValidationBanner(violations = saveStatus.violations)
            is SaveStatus.Error -> InlineStatus(StatusKind.Error, "Save failed: ${saveStatus.message}")
        }
        when (publishStatus) {
            PublishStatus.Idle -> Unit
            PublishStatus.Publishing -> InlineStatus(StatusKind.Accent, "Publishing…")
            is PublishStatus.Published -> InlineStatus(StatusKind.Success, "Published v${publishStatus.version}.")
            is PublishStatus.Error -> InlineStatus(StatusKind.Error, "Publish failed: ${publishStatus.message}")
        }
        if (decodeError != null) {
            InlineStatus(StatusKind.Error, "JSON does not decode as Screen: $decodeError")
        }
    }
}

/**
 * The raw-JSON editing pane: a borderless [BasicTextField] on the code-well background with
 * monospace text and an accent cursor. A real code editor (line numbers, highlighting) is a
 * deliberate non-goal for now — the field is a faithful, fast plain-text surface.
 */
@Composable
private fun JsonEditorPane(
    text: String,
    onTextChange: (String) -> Unit,
    decodeError: String?,
    modifier: Modifier = Modifier,
) {
    StudioPanel(
        title = "SCREEN JSON",
        contentPadding = 0.dp,
        headerActions = {
            if (decodeError == null) {
                StatusChip(text = "Valid", kind = ChipKind.Success)
            } else {
                StatusChip(text = "Decode error", kind = ChipKind.Error)
            }
        },
        modifier = modifier,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = CODE_FONT_SIZE,
                lineHeight = CODE_LINE_HEIGHT,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .background(studioColors.codeBg)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        )
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
    var previewDark by remember { mutableStateOf(false) }
    StudioPanel(
        title = "PREVIEW",
        contentPadding = 0.dp,
        headerActions = {
            if (hasDecodeError) {
                StatusChip(text = "Stale", kind = ChipKind.Warning)
            }
            IconButton(onClick = { previewDark = !previewDark }, modifier = Modifier.size(PREVIEW_TOGGLE_SIZE)) {
                Icon(
                    imageVector = if (previewDark) StudioIcons.Sun else StudioIcons.Moon,
                    contentDescription = if (previewDark) "Switch preview to light" else "Switch preview to dark",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PREVIEW_TOGGLE_ICON_SIZE),
                )
            }
        },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (screen == null) {
                EmptyState(
                    title = "No preview yet",
                    hint = "Type valid Screen JSON on the left to render a live preview.",
                    icon = StudioIcons.Code,
                )
            } else {
                DevicePreviewFrame(dark = previewDark) {
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
            HistoryState.Loading -> LoadingState(label = "Loading history…")
            is HistoryState.Error -> ErrorState(message = "Could not load history: ${s.message}")
            is HistoryState.Ready -> if (s.versions.isEmpty()) {
                EmptyState(
                    title = "No published versions yet",
                    hint = "Publish a draft to create version 1.",
                    icon = StudioIcons.Audit,
                )
            } else {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InlineStatus(
                        kind = StatusKind.Neutral,
                        text = "Per-version JSON preview requires a server endpoint that doesn't yet exist " +
                            "(GET /admin/screens/{id}/versions/{n}); listed read-only for now.",
                    )
                    StudioPanel(contentPadding = 0.dp, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            s.versions.forEachIndexed { index, version ->
                                VersionRow(
                                    version = version,
                                    isAdmin = isAdmin,
                                    onRevertClick = { revertTarget = version.version },
                                )
                                if (index != s.versions.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    revertTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!revertInFlight) revertTarget = null },
            icon = {
                Icon(
                    imageVector = StudioIcons.Audit,
                    contentDescription = null,
                    tint = studioColors.warning,
                )
            },
            title = { Text("Revert to v$target?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Reverting will create a new published version that copies the body from " +
                            "v$target. The current head is not deleted.",
                    )
                    revertError?.let {
                        InlineStatus(StatusKind.Error, it)
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusChip(text = "v${version.version}", kind = ChipKind.Accent)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "published: ${version.publishedAt ?: "—"}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = "by editor ${version.editorId}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isAdmin) {
            ToolbarOutlinedButton(text = "Revert", icon = StudioIcons.Audit, onClick = onRevertClick)
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
private val CODE_FONT_SIZE = 13.sp
private val CODE_LINE_HEIGHT = 19.sp
private val TOOLBAR_SPINNER_SIZE = 16.dp
private val TOOLBAR_SPINNER_STROKE = 2.dp
private val PREVIEW_TOGGLE_SIZE = 26.dp
private val PREVIEW_TOGGLE_ICON_SIZE = 14.dp
