package dev.sdui.kmp.tooling.telemetry.sentry

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.StatePath
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives [SentryTelemetry] against a real (initialised) Sentry SDK and asserts the
 * breadcrumbs / events the adapter emits land on the SDK as expected.
 *
 * We do not implement [io.sentry.IHub] manually — the interface has dozens of methods
 * that the SDK considers public surface. Instead, we initialise Sentry with a stub DSN
 * and intercept every breadcrumb / event via the public `beforeBreadcrumb` /
 * `beforeSend` hooks. Returning `null` from the hooks drops the signal so nothing
 * actually leaves the JVM, but we still capture the value the adapter produced.
 */
class SentryTelemetryTest {

    private val capturedBreadcrumbs: MutableList<Breadcrumb> = mutableListOf()
    private val capturedEvents: MutableList<SentryEvent> = mutableListOf()

    @BeforeTest
    fun initSentry() {
        capturedBreadcrumbs.clear()
        capturedEvents.clear()
        // Stub DSN — required for `Sentry.init` to consider the SDK enabled. The transport
        // factory is replaced via `beforeSend` returning null, so nothing actually ships.
        Sentry.init { options: SentryOptions ->
            options.dsn = "https://public@test.invalid/1"
            // Keep DEBUG breadcrumbs alive — the adapter uses DEBUG for `onNodeRendered`,
            // and Sentry's default is to buffer all levels (DEBUG -> INFO -> WARN -> ERROR).
            // No filter required here, but we set maxBreadcrumbs > our test inputs to be safe.
            options.maxBreadcrumbs = 100
            options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _: Hint ->
                capturedBreadcrumbs += crumb
                crumb
            }
            options.setBeforeSend { event: SentryEvent, _: Hint ->
                capturedEvents += event
                // Returning null drops the event; the SDK will not transmit it.
                null
            }
        }
    }

    @AfterTest
    fun closeSentry() {
        Sentry.close()
    }

    @Test
    fun onScreenRendered_adds_info_breadcrumb_with_screen_attributes() {
        val telemetry = SentryTelemetry()
        telemetry.onScreenRendered(ScreenId("home"), SchemaVersion.V1, durationMs = 42L)

        val crumb = capturedBreadcrumbs.single()
        assertEquals("sdui.screen", crumb.category)
        assertEquals(SentryLevel.INFO, crumb.level)
        assertEquals("home", crumb.data["sdui.screen.id"])
        assertEquals(1, crumb.data["sdui.schema_version"])
        assertEquals(42L, crumb.data["sdui.screen.render.duration_ms"])
        assertTrue(capturedEvents.isEmpty(), "screen renders should not capture events")
    }

    @Test
    fun onNodeRendered_adds_debug_breadcrumb_per_node() {
        val telemetry = SentryTelemetry()
        telemetry.onNodeRendered("text", SchemaVersion.V1)
        telemetry.onNodeRendered("button", SchemaVersion.V1)

        assertEquals(2, capturedBreadcrumbs.size)
        capturedBreadcrumbs.forEach { crumb ->
            assertEquals("sdui.node", crumb.category)
            assertEquals(SentryLevel.DEBUG, crumb.level)
        }
        assertEquals("text", capturedBreadcrumbs[0].data["sdui.node.type"])
        assertEquals("button", capturedBreadcrumbs[1].data["sdui.node.type"])
        assertTrue(capturedEvents.isEmpty(), "node renders should not capture events")
    }

    @Test
    fun onUnknownNode_captures_warning_event_and_drops_breadcrumb() {
        val telemetry = SentryTelemetry()
        telemetry.onUnknownNode("future_widget", listOf(NodeId("root"), NodeId("child")))

        val crumb = capturedBreadcrumbs.single()
        assertEquals("sdui.unknown_node", crumb.category)
        assertEquals(SentryLevel.WARNING, crumb.level)
        assertEquals("future_widget", crumb.data["sdui.node.type"])
        assertEquals("root/child", crumb.data["sdui.node.trace"])

        val event = capturedEvents.single()
        assertEquals(SentryLevel.WARNING, event.level)
        assertNotNull(event.message)
        assertEquals("sdui.unknown_node: future_widget", event.message?.formatted)
        assertEquals("future_widget", event.tags?.get("sdui.node.type"))
        assertEquals("root/child", event.tags?.get("sdui.node.trace"))
    }

    @Test
    fun onActionDispatched_adds_breadcrumb_with_discriminator_only() {
        val telemetry = SentryTelemetry()
        telemetry.onActionDispatched(Action.Navigate(Destination.Back()), durationMs = 7L)

        val crumb = capturedBreadcrumbs.single()
        assertEquals("sdui.action", crumb.category)
        assertEquals(SentryLevel.INFO, crumb.level)
        // We deliberately ship only the discriminator, never the Action's payload, so
        // user state cannot leak into Sentry's transport.
        assertEquals("Navigate", crumb.data["sdui.action.type"])
        assertEquals(7L, crumb.data["sdui.action.dispatch.duration_ms"])
        assertTrue(capturedEvents.isEmpty(), "action dispatches should not capture events")
    }

    @Test
    fun onBindingError_captures_warning_event_and_drops_breadcrumb() {
        val telemetry = SentryTelemetry()
        telemetry.onBindingError(StatePath("user.name"), expected = "string", got = "number")

        val crumb = capturedBreadcrumbs.single()
        assertEquals("sdui.binding_error", crumb.category)
        assertEquals(SentryLevel.WARNING, crumb.level)
        assertEquals("user.name", crumb.data["sdui.state.path"])
        assertEquals("string", crumb.data["sdui.binding.expected"])
        assertEquals("number", crumb.data["sdui.binding.got"])

        val event = capturedEvents.single()
        assertEquals(SentryLevel.WARNING, event.level)
        assertEquals("user.name", event.tags?.get("sdui.state.path"))
        assertEquals("string", event.tags?.get("sdui.binding.expected"))
        assertEquals("number", event.tags?.get("sdui.binding.got"))
    }
}
