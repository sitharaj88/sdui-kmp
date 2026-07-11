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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.AudienceSummary
import dev.sdui.kmp.studio.web.api.ExperimentSummary
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.api.VariantCount
import dev.sdui.kmp.studio.web.api.VariantSummary

/**
 * M-S6 Experiments tab.
 *
 * Master view: filterable list of experiments. Detail view: variants with weight sliders +
 * sum-to-100 validation, audience picker, status transitions, and a results pane with
 * text-based bar charts (no third-party chart lib — see ADR-0018 §"Sample-server delivery
 * shape" for the rationale on declining new dependencies).
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
            api = api,
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
    api: StudioApi,
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
    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = screenIdFilter,
                        onValueChange = onScreenIdFilterChange,
                        label = { Text("Screen ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = statusFilter,
                        onValueChange = onStatusFilterChange,
                        label = { Text("Status (draft/active/paused/completed)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row {
                    Button(onClick = onApply) { Text("Apply filters") }
                    OutlinedButton(
                        onClick = {
                            onScreenIdFilterChange("")
                            onStatusFilterChange("")
                            onApply()
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Clear") }
                }
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            errorText != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Could not load experiments: $errorText")
            }
            experiments.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No experiments match the current filters.")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = experiments, key = { it.id }) { exp ->
                    ExperimentRow(exp = exp, onClick = { onSelect(exp.id) })
                }
            }
        }
    }
}

@Composable
private fun ExperimentRow(exp: ExperimentSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(text = exp.name, style = MaterialTheme.typography.titleMedium)
                Box(modifier = Modifier.weight(1f))
                Text(text = exp.status, style = MaterialTheme.typography.labelMedium)
            }
            Text(text = "id: ${exp.id}", style = MaterialTheme.typography.bodySmall)
            Text(text = "screen: ${exp.screenId}", style = MaterialTheme.typography.bodySmall)
            exp.description?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(onClick = onClick) { Text("Open") }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back to list") }
            Text(text = experimentId, modifier = Modifier.padding(start = 8.dp))
        }
        TabRow(selectedTabIndex = tab.ordinal) {
            ExperimentDetailTab.values().forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = { Text(entry.label) },
                )
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
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
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(text = "Total weight: $totalWeight / 100", style = MaterialTheme.typography.bodySmall)
        if (totalWeight != WEIGHT_FULL && variants.isNotEmpty()) {
            Text(
                text = "Sum must be exactly 100 to start — currently $totalWeight.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(items = variants, key = { it.id }) { v ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row {
                            Text(text = v.name, style = MaterialTheme.typography.titleSmall)
                            Box(modifier = Modifier.weight(1f))
                            Text(text = "${v.weight}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(text = "version: ${v.screenVersionId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Available audiences", style = MaterialTheme.typography.titleSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            items(items = audiences, key = { it.id }) { a ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(text = a.name, style = MaterialTheme.typography.bodyMedium)
                        Text(text = "id: ${a.id}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { pickedId = a.id }) { Text("Pick") }
                        }
                    }
                }
            }
        }
        Row {
            OutlinedTextField(
                value = pickedId,
                onValueChange = { pickedId = it },
                label = { Text("Audience ID") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    // No suspending body here — Compose Wasm doesn't have rememberCoroutineScope on
                    // every release we target, so we delegate via LaunchedEffect on a tick instead.
                    pickedId = pickedId.trim()
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Stage") }
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
    Row {
        Button(onClick = { inflight = true }, enabled = !inflight) {
            Text(if (inflight) "Linking…" else "Link $audienceId")
        }
        resultText?.let {
            Text(text = it, modifier = Modifier.padding(start = 8.dp))
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
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Status transitions", style = MaterialTheme.typography.titleSmall)
        Row {
            listOf("draft", "active", "paused", "completed").forEach { s ->
                OutlinedButton(
                    onClick = { pendingStatus = s },
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text(s) }
            }
        }
        resultText?.let { Text(it) }
    }
}

@Composable
private fun ResultsPane(counts: List<VariantCount>) {
    val total = counts.sumOf { it.count }.coerceAtLeast(1L)
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Assignment counts (n=${counts.sumOf { it.count }})", style = MaterialTheme.typography.titleSmall)
        counts.forEach { c ->
            // Text-based bar — count out of CHART_WIDTH characters, so the eye can read the ratio
            // without a chart library. We considered inline SVG via skiko; the trade-off was an
            // extra ~200 KB to the wasm bundle for cosmetics, which ADR-0018 declines.
            val barLen = ((c.count * CHART_WIDTH) / total).toInt().coerceAtLeast(1).coerceAtMost(CHART_WIDTH)
            Row {
                Text(text = c.variantId.padEnd(VARIANT_LABEL_PAD), modifier = Modifier.padding(end = 8.dp))
                Text(text = "█".repeat(barLen) + " ${c.count}")
            }
        }
        if (counts.isEmpty()) Text("No assignments recorded yet.")
    }
}

private enum class ExperimentDetailTab(val label: String) {
    Variants("Variants"),
    Audiences("Audiences"),
    Status("Status"),
    Results("Results"),
}

private const val WEIGHT_FULL = 100
private const val CHART_WIDTH = 30
private const val VARIANT_LABEL_PAD = 12
