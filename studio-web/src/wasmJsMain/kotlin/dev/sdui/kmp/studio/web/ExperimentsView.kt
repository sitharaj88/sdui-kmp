package dev.sdui.kmp.studio.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.AudienceSummary
import dev.sdui.kmp.studio.web.api.ExperimentSummary
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.api.VariantCount
import dev.sdui.kmp.studio.web.api.VariantSummary
import dev.sdui.kmp.studio.web.components.ChipKind
import dev.sdui.kmp.studio.web.components.EmptyState
import dev.sdui.kmp.studio.web.components.ErrorState
import dev.sdui.kmp.studio.web.components.InlineStatus
import dev.sdui.kmp.studio.web.components.LoadingState
import dev.sdui.kmp.studio.web.components.StatusChip
import dev.sdui.kmp.studio.web.components.StatusKind
import dev.sdui.kmp.studio.web.components.StudioPanel
import dev.sdui.kmp.studio.web.components.StudioTabs
import dev.sdui.kmp.studio.web.components.ToolbarButton
import dev.sdui.kmp.studio.web.components.ToolbarOutlinedButton
import dev.sdui.kmp.studio.web.components.ToolbarTextButton
import dev.sdui.kmp.studio.web.components.experimentStatusChipKind
import dev.sdui.kmp.studio.web.components.studioFieldColors
import dev.sdui.kmp.studio.web.theme.StudioIcons

/**
 * M-S6 Experiments tab.
 *
 * Master view: filterable list of experiments. Detail view: variants with weight bars +
 * sum-to-100 validation, audience picker, status transitions, and a results pane with
 * Compose-drawn bars (plain `Box` tracks/fills — still no third-party chart lib, per
 * ADR-0018 §"Sample-server delivery shape").
 */
@Composable
public fun ExperimentsView(api: StudioApi) {
    var experiments by remember { mutableStateOf<List<ExperimentSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusFilter by remember { mutableStateOf("") }
    var screenIdFilter by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    var selectedExperimentId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        loading = true
        errorText = null
        try {
            experiments = api.listExperiments(
                screenId = screenIdFilter.trim().ifBlank { null },
                status = statusFilter.trim().ifBlank { null },
            )
        } catch (t: Throwable) {
            errorText = t.message ?: t::class.simpleName ?: "unknown error"
        } finally {
            loading = false
        }
    }

    if (selectedExperimentId == null) {
        ExperimentsList(
            experiments = experiments,
            loading = loading,
            errorText = errorText,
            statusFilter = statusFilter,
            onStatusFilterChange = { statusFilter = it },
            screenIdFilter = screenIdFilter,
            onScreenIdFilterChange = { screenIdFilter = it },
            onApply = { refreshTick += 1 },
            onSelect = { selectedExperimentId = it },
        )
    } else {
        ExperimentDetail(
            api = api,
            experimentId = selectedExperimentId!!,
            onBack = {
                selectedExperimentId = null
                refreshTick += 1
            },
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun ExperimentsList(
    experiments: List<ExperimentSummary>,
    loading: Boolean,
    errorText: String?,
    statusFilter: String,
    onStatusFilterChange: (String) -> Unit,
    screenIdFilter: String,
    onScreenIdFilterChange: (String) -> Unit,
    onApply: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioPanel(title = "FILTERS", modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = screenIdFilter,
                        onValueChange = onScreenIdFilterChange,
                        label = { Text("Screen ID") },
                        singleLine = true,
                        colors = studioFieldColors(),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = statusFilter,
                        onValueChange = onStatusFilterChange,
                        label = { Text("Status (draft/active/paused/completed)") },
                        singleLine = true,
                        colors = studioFieldColors(),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolbarButton(text = "Apply filters", onClick = onApply)
                    ToolbarTextButton(
                        text = "Clear",
                        onClick = {
                            onScreenIdFilterChange("")
                            onStatusFilterChange("")
                            onApply()
                        },
                    )
                }
            }
        }
        when {
            loading -> LoadingState(label = "Loading experiments…")
            errorText != null -> ErrorState(message = "Could not load experiments: $errorText", onRetry = onApply)
            experiments.isEmpty() -> EmptyState(
                title = "No experiments",
                hint = "Nothing matches the current filters.",
                icon = StudioIcons.Experiments,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = experiments, key = { it.id }) { exp ->
                    ExperimentRow(exp = exp, onClick = { onSelect(exp.id) })
                }
            }
        }
    }
}

/** One clickable bordered row per experiment: name + status chip, monospace ids, description. */
@Composable
private fun ExperimentRow(exp: ExperimentSummary, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = exp.name, style = MaterialTheme.typography.titleSmall)
                StatusChip(text = exp.status, kind = experimentStatusChipKind(exp.status))
                Box(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = StudioIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(CHEVRON_SIZE),
                )
            }
            Text(
                text = "${exp.id}  ·  screen ${exp.screenId}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            exp.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun ExperimentDetail(api: StudioApi, experimentId: String, onBack: () -> Unit) {
    var tab by remember(experimentId) { mutableStateOf(ExperimentDetailTab.Variants) }
    var variants by remember(experimentId) { mutableStateOf<List<VariantSummary>>(emptyList()) }
    var audiences by remember(experimentId) { mutableStateOf<List<AudienceSummary>>(emptyList()) }
    var counts by remember(experimentId) { mutableStateOf<List<VariantCount>>(emptyList()) }
    var loadTick by remember(experimentId) { mutableStateOf(0) }

    LaunchedEffect(loadTick) {
        runCatching {
            variants = api.listVariants(experimentId)
            audiences = api.listAudiences()
            counts = runCatching { api.experimentResults(experimentId) }.getOrDefault(emptyList())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            TextButton(onClick = onBack) {
                Icon(
                    imageVector = StudioIcons.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(BACK_ICON_SIZE),
                )
                Text("  Back to list", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = experimentId,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        StudioTabs(
            labels = ExperimentDetailTab.entries.map { it.label },
            selectedIndex = tab.ordinal,
            onSelect = { tab = ExperimentDetailTab.entries[it] },
        )
        Box(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
            when (tab) {
                ExperimentDetailTab.Variants -> VariantsPane(variants = variants)
                ExperimentDetailTab.Audiences -> AudiencesPane(
                    api = api,
                    experimentId = experimentId,
                    audiences = audiences,
                    onLinked = { loadTick += 1 },
                )
                ExperimentDetailTab.Status -> StatusPane(
                    api = api,
                    experimentId = experimentId,
                    onChanged = { loadTick += 1 },
                )
                ExperimentDetailTab.Results -> ResultsPane(counts = counts)
            }
        }
    }
}

@Composable
private fun VariantsPane(variants: List<VariantSummary>) {
    val totalWeight = variants.sumOf { it.weight }
    val weightsValid = totalWeight == WEIGHT_FULL
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Total weight", style = MaterialTheme.typography.bodySmall)
            StatusChip(
                text = "$totalWeight / $WEIGHT_FULL",
                kind = if (weightsValid) ChipKind.Success else ChipKind.Warning,
            )
            if (!weightsValid && variants.isNotEmpty()) {
                Text(
                    text = "Sum must be exactly $WEIGHT_FULL to start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items = variants, key = { it.id }) { v ->
                VariantRow(v = v)
            }
        }
    }
}

/** Variant card with a proportional weight bar (plain Box track + primary fill). */
@Composable
private fun VariantRow(v: VariantSummary) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = v.name, style = MaterialTheme.typography.titleSmall)
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = "${v.weight}%",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WEIGHT_BAR_HEIGHT)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, WEIGHT_BAR_SHAPE),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (v.weight.coerceIn(0, WEIGHT_FULL)).toFloat() / WEIGHT_FULL)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, WEIGHT_BAR_SHAPE),
                )
            }
            Text(
                text = "version: ${v.screenVersionId}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudiencesPane(
    api: StudioApi,
    experimentId: String,
    audiences: List<AudienceSummary>,
    onLinked: () -> Unit,
) {
    var pickedId by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioPanel(title = "AVAILABLE AUDIENCES", contentPadding = 0.dp, modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = audiences, key = { it.id }) { a ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(text = a.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = a.id,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pickedId = a.id }) { Text("Pick") }
                    }
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pickedId,
                onValueChange = { pickedId = it },
                label = { Text("Audience ID") },
                singleLine = true,
                colors = studioFieldColors(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(
                text = "Stage",
                onClick = {
                    // No suspending body here — Compose Wasm doesn't have rememberCoroutineScope on
                    // every release we target, so we delegate via LaunchedEffect on a tick instead.
                    pickedId = pickedId.trim()
                },
            )
        }
        if (pickedId.isNotBlank()) {
            LinkConfirm(
                api = api,
                experimentId = experimentId,
                audienceId = pickedId,
                onLinked = {
                    pickedId = ""
                    onLinked()
                },
            )
        }
    }
}

@Composable
private fun LinkConfirm(api: StudioApi, experimentId: String, audienceId: String, onLinked: () -> Unit) {
    var inflight by remember(audienceId) { mutableStateOf(false) }
    var resultText by remember(audienceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(audienceId, inflight) {
        if (!inflight) return@LaunchedEffect
        val ok = api.linkAudience(experimentId, audienceId)
        resultText = if (ok) "linked." else "failed."
        inflight = false
        if (ok) onLinked()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolbarButton(
            text = if (inflight) "Linking…" else "Link $audienceId",
            onClick = { inflight = true },
            enabled = !inflight,
        )
        resultText?.let {
            InlineStatus(kind = if (it == "linked.") StatusKind.Success else StatusKind.Error, text = it)
        }
    }
}

@Composable
private fun StatusPane(api: StudioApi, experimentId: String, onChanged: () -> Unit) {
    var pendingStatus by remember(experimentId) { mutableStateOf<String?>(null) }
    var resultText by remember(experimentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingStatus) {
        val s = pendingStatus ?: return@LaunchedEffect
        val ok = api.setExperimentStatus(experimentId, s)
        resultText = if (ok) "now $s" else "failed to set $s"
        pendingStatus = null
        if (ok) onChanged()
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Status transitions", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("draft", "active", "paused", "completed").forEach { s ->
                ToolbarOutlinedButton(text = s, onClick = { pendingStatus = s })
            }
        }
        resultText?.let {
            InlineStatus(
                kind = if (it.startsWith("now ")) StatusKind.Success else StatusKind.Error,
                text = it,
            )
        }
    }
}

/**
 * Assignment counts as real Compose bars: a muted track `Box` with a primary fill sized by
 * `fillMaxWidth(fraction)`. Still no chart library (ADR-0018) — these are plain boxes.
 */
@Composable
private fun ResultsPane(counts: List<VariantCount>) {
    val total = counts.sumOf { it.count }.coerceAtLeast(1L)
    StudioPanel(
        title = "ASSIGNMENT COUNTS (N=${counts.sumOf { it.count }})",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            counts.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = c.variantId,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(RESULT_LABEL_WIDTH),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(RESULT_BAR_HEIGHT)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, WEIGHT_BAR_SHAPE),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = (c.count.toFloat() / total).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, WEIGHT_BAR_SHAPE),
                        )
                    }
                    Text(
                        text = c.count.toString(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(RESULT_COUNT_WIDTH),
                    )
                }
            }
            if (counts.isEmpty()) {
                Text(
                    text = "No assignments recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class ExperimentDetailTab(val label: String) {
    Variants("Variants"),
    Audiences("Audiences"),
    Status("Status"),
    Results("Results"),
}

private const val WEIGHT_FULL = 100
private val WEIGHT_BAR_HEIGHT = 6.dp
private val RESULT_BAR_HEIGHT = 16.dp
private val WEIGHT_BAR_SHAPE = RoundedCornerShape(3.dp)
private val RESULT_LABEL_WIDTH = 120.dp
private val RESULT_COUNT_WIDTH = 64.dp
private val CHEVRON_SIZE = 16.dp
private val BACK_ICON_SIZE = 16.dp
