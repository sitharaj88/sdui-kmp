package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.SchemaVersion
import kotlin.reflect.KClass

/**
 * Generic [NodeRenderer] for [NativeSurface]. Register it once with the [WidgetRegistry] via
 * [NativeSurfaces.register]; the actual platform-specific rendering happens inside a
 * [NativeSurfaceFactory] registered in a [NativeSurfaceRegistry].
 *
 * When the [LocalNativeSurfaceRegistry] has no factory for [NativeSurface.kind], this
 * renderer emits an `onUnknownNode` telemetry event and renders [NativeSurface.fallback] —
 * mirroring how unknown node discriminators behave. This is invariant #3 applied at the
 * sub-widget level.
 */
public object NativeSurfaceNodeRenderer : NodeRenderer<NativeSurface> {
    override val nodeClass: KClass<NativeSurface> = NativeSurface::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: NativeSurface, modifier: Modifier) {
        val registry = LocalNativeSurfaceRegistry.current
        val telemetry = LocalTelemetry.current
        val accessibleModifier = modifier.applyA11y(node.a11y, LocalStateStore.current)
        val factory = registry.factoryFor(node.kind, node.since)
        if (factory != null) {
            factory.Render(node, accessibleModifier)
        } else {
            telemetry.onUnknownNode("native:${node.kind}", listOf(node.id))
            val fb = node.fallback
            if (fb != null) RenderNode(fb, accessibleModifier)
        }
    }
}

/** One-line registration helper mirroring [dev.sdui.kmp.widgetscore.WidgetsCore.register]. */
public object NativeSurfaces {
    public fun register(builder: WidgetRegistry.Builder): WidgetRegistry.Builder =
        builder.register(NativeSurfaceNodeRenderer)
}
