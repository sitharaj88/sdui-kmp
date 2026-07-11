package dev.sdui.kmp.tooling.telemetry.sentry

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.runtime.SduiTelemetry
import io.sentry.Breadcrumb
import io.sentry.IHub
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message

/**
 * [SduiTelemetry] adapter that forwards every runtime hook to a Sentry pipeline.
 *
 * Wire one instance into [dev.sdui.kmp.runtime.SduiHost] and renderer / dispatcher events
 * become breadcrumbs that ride along on the next exception captured anywhere in the host.
 * Unknown-node fallbacks and binding errors additionally fire a low-severity Sentry event so
 * an on-call engineer sees the regression even if no JVM `Throwable` is raised.
 *
 * Signal mapping (chosen to keep the breadcrumb stream useful without flooding Sentry):
 *
 *  * [onScreenRendered] — INFO breadcrumb under category `sdui.screen`. Carries the screen id
 *    plus the schema version so backwards-compat regressions are visible at a glance.
 *  * [onNodeRendered] — DEBUG breadcrumb under `sdui.node`. Sentry drops DEBUG breadcrumbs
 *    by default unless the SDK is configured to keep them, so per-node renders do not blow
 *    the breadcrumb buffer in production.
 *  * [onUnknownNode] — captures a [SentryEvent] at WARNING level with the node trace as
 *    a tag. Triggers the same alert pipeline that surfaces handled exceptions.
 *  * [onActionDispatched] — INFO breadcrumb `sdui.action`. The breadcrumb carries the action
 *    discriminator (its `simpleName`, not the payload) so we never accidentally leak user
 *    state into Sentry's transport.
 *  * [onBindingError] — captures a [SentryEvent] at WARNING level with the path / expected /
 *    got tuple. Bindings should never fail in steady state, so this is a real signal.
 *
 * Example wiring:
 *
 * ```kotlin
 * Sentry.init { options ->
 *     options.dsn = System.getenv("SENTRY_DSN")
 *     options.release = "sdui-kmp@1.0.0"
 *     options.environment = "production"
 * }
 * SduiHost(
 *     screen = screen,
 *     registry = registry,
 *     telemetry = SentryTelemetry(),
 * )
 * ```
 *
 * Alternatives:
 *
 *  * Pass an explicit [IHub] when running in a multi-hub setup (e.g. tests, server-side
 *    request-scoped hubs). The default constructor uses [Sentry.getCurrentHub] which is the
 *    process-wide global most apps want.
 *
 * @property hub the Sentry hub events are routed to. Defaults to the process-global hub
 *   so adopters do not need to plumb it through DI; pass an explicit value in tests.
 */
public class SentryTelemetry(
    private val hub: IHub = Sentry.getCurrentHub(),
) : SduiTelemetry {

    override fun onScreenRendered(id: ScreenId, version: SchemaVersion, durationMs: Long) {
        val crumb = Breadcrumb.info("rendered ${id.value}").apply {
            category = CATEGORY_SCREEN
            type = "navigation"
            setData("sdui.screen.id", id.value)
            setData("sdui.schema_version", version.value)
            setData("sdui.screen.render.duration_ms", durationMs)
        }
        hub.addBreadcrumb(crumb)
    }

    override fun onNodeRendered(type: String, version: SchemaVersion) {
        val crumb = Breadcrumb().apply {
            level = SentryLevel.DEBUG
            category = CATEGORY_NODE
            message = type
            setData("sdui.node.type", type)
            setData("sdui.schema_version", version.value)
        }
        hub.addBreadcrumb(crumb)
    }

    override fun onUnknownNode(type: String, trace: List<NodeId>) {
        // Drop a breadcrumb FIRST so the captured event sees it in its trail.
        val traceString = trace.joinToString(separator = "/") { it.value }
        val crumb = Breadcrumb().apply {
            level = SentryLevel.WARNING
            category = CATEGORY_UNKNOWN_NODE
            message = type
            setData("sdui.node.type", type)
            setData("sdui.node.trace", traceString)
        }
        hub.addBreadcrumb(crumb)

        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply {
                formatted = "sdui.unknown_node: $type"
            }
            setTag("sdui.node.type", type)
            setTag("sdui.node.trace", traceString)
            setExtra("sdui.node.trace_length", trace.size)
        }
        hub.captureEvent(event)
    }

    override fun onActionDispatched(action: Action, durationMs: Long) {
        // Action subtypes carry user data on the wire (e.g. `Action.UpdateState.value`).
        // Send only the discriminator so we cannot leak state into Sentry's transport.
        val type = actionDiscriminator(action)
        val crumb = Breadcrumb.info("dispatched $type").apply {
            category = CATEGORY_ACTION
            setData("sdui.action.type", type)
            setData("sdui.action.dispatch.duration_ms", durationMs)
        }
        hub.addBreadcrumb(crumb)
    }

    override fun onBindingError(path: StatePath, expected: String, got: String) {
        val crumb = Breadcrumb().apply {
            level = SentryLevel.WARNING
            category = CATEGORY_BINDING_ERROR
            message = "binding error at ${path.value}"
            setData("sdui.state.path", path.value)
            setData("sdui.binding.expected", expected)
            setData("sdui.binding.got", got)
        }
        hub.addBreadcrumb(crumb)

        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply {
                formatted = "sdui.binding_error at ${path.value}: expected=$expected got=$got"
            }
            setTag("sdui.state.path", path.value)
            setTag("sdui.binding.expected", expected)
            setTag("sdui.binding.got", got)
        }
        hub.captureEvent(event)
    }

    /**
     * Map an [Action] to a low-cardinality string for Sentry tagging. We use Kotlin's
     * `simpleName` (e.g. `Navigate`, `Submit`, `UpdateState`) rather than the
     * `@SerialName` annotation because reading annotations would require kotlin-reflect,
     * which we deliberately do not pull into a tooling adapter.
     */
    private fun actionDiscriminator(action: Action): String =
        action::class.simpleName ?: "unknown"

    public companion object {
        private const val CATEGORY_SCREEN: String = "sdui.screen"
        private const val CATEGORY_NODE: String = "sdui.node"
        private const val CATEGORY_UNKNOWN_NODE: String = "sdui.unknown_node"
        private const val CATEGORY_ACTION: String = "sdui.action"
        private const val CATEGORY_BINDING_ERROR: String = "sdui.binding_error"
    }
}
