package dev.sdui.kmp.tooling.telemetry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.runtime.ConsentScope
import dev.sdui.kmp.runtime.ConsentSource
import dev.sdui.kmp.runtime.LocalConsentSource
import dev.sdui.kmp.runtime.SduiTelemetry

/**
 * [SduiTelemetry] decorator that drops every event when the supplied [consent] source reports
 * that [ConsentScope.Telemetry] is not granted.
 *
 * The whole point of this class is to make consent gating a pure Kotlin construct so it works
 * outside Compose too — for tests, headless servers, and any code path that materialises an
 * `SduiTelemetry` without going through [LocalConsentSource]. Construct one explicitly and
 * pass it to whatever pipeline you have:
 *
 * ```kotlin
 * val telemetry = ConsentAwareTelemetry(
 *     delegate = OpenTelemetryTelemetry(otel),
 *     consent = userPrefsConsentSource,
 * )
 * SduiHost(telemetry = telemetry, ...)
 * ```
 *
 * For Composable wiring, prefer [rememberConsentAwareTelemetry] — it captures the active
 * [LocalConsentSource] automatically.
 *
 * Consent is queried lazily on every emission; switching the user's preference at runtime
 * takes effect on the very next event without rebuilding the pipeline.
 */
public class ConsentAwareTelemetry(
    private val delegate: SduiTelemetry,
    private val consent: ConsentSource,
) : SduiTelemetry {

    private fun granted(): Boolean = consent.isGranted(ConsentScope.Telemetry)

    override fun onScreenRendered(id: ScreenId, version: SchemaVersion, durationMs: Long) {
        if (granted()) delegate.onScreenRendered(id, version, durationMs)
    }

    override fun onNodeRendered(type: String, version: SchemaVersion) {
        if (granted()) delegate.onNodeRendered(type, version)
    }

    override fun onUnknownNode(type: String, trace: List<NodeId>) {
        if (granted()) delegate.onUnknownNode(type, trace)
    }

    override fun onActionDispatched(action: Action, durationMs: Long) {
        if (granted()) delegate.onActionDispatched(action, durationMs)
    }

    override fun onBindingError(path: StatePath, expected: String, got: String) {
        if (granted()) delegate.onBindingError(path, expected, got)
    }

    override fun onNodeBudgetExceeded(type: String, id: NodeId, depth: Int) {
        if (granted()) delegate.onNodeBudgetExceeded(type, id, depth)
    }

    override fun onRenderFailure(type: String, id: NodeId, error: Throwable) {
        if (granted()) delegate.onRenderFailure(type, id, error)
    }
}

/**
 * Composable factory that wraps [delegate] in a [ConsentAwareTelemetry] backed by the active
 * [LocalConsentSource]. Designed for the typical wiring pattern:
 *
 * ```kotlin
 * @Composable
 * fun App() {
 *     val telemetry = rememberConsentAwareTelemetry(OpenTelemetryTelemetry.usingGlobal())
 *     SduiHost(telemetry = telemetry, ...)
 * }
 * ```
 *
 * The returned instance is `remember`ed against [delegate] and the resolved [ConsentSource];
 * switching either invalidates and rebuilds, but updates inside an existing source (e.g. the
 * user toggling consent in settings) take effect on the next emission without recomposition.
 */
@Composable
public fun rememberConsentAwareTelemetry(delegate: SduiTelemetry): SduiTelemetry {
    val source = LocalConsentSource.current
    return remember(delegate, source) { ConsentAwareTelemetry(delegate, source) }
}
