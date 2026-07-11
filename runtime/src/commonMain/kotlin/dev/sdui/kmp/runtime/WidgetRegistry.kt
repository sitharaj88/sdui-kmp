package dev.sdui.kmp.runtime

import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.UiNode
import kotlin.reflect.KClass

/**
 * Compile-time-typed dispatch table from [UiNode] class to [NodeRenderer].
 *
 * Widget modules register their renderers via [Builder]. Lookups are O(1) by Kotlin class;
 * if the runtime [clientVersion] falls outside the renderer's [NodeRenderer.handledVersions]
 * the lookup returns null and the caller renders the node's fallback.
 *
 * No reflection. No `when (node)`. Adding a widget type is a one-line registration.
 */
public class WidgetRegistry private constructor(
    private val renderers: Map<KClass<out UiNode>, NodeRenderer<*>>,
    public val clientVersion: SchemaVersion,
) {
    @Suppress("UNCHECKED_CAST")
    public fun <T : UiNode> rendererFor(node: T): NodeRenderer<T>? {
        val renderer = renderers[node::class] as? NodeRenderer<T> ?: return null
        return if (clientVersion in renderer.handledVersions) renderer else null
    }

    public class Builder(private val clientVersion: SchemaVersion) {
        private val renderers: MutableMap<KClass<out UiNode>, NodeRenderer<*>> = mutableMapOf()

        public fun <T : UiNode> register(renderer: NodeRenderer<T>): Builder = apply {
            renderers[renderer.nodeClass] = renderer
        }

        public fun build(): WidgetRegistry = WidgetRegistry(renderers.toMap(), clientVersion)
    }

    public companion object {
        public fun build(
            clientVersion: SchemaVersion = SchemaVersion.V1,
            configure: Builder.() -> Unit,
        ): WidgetRegistry = Builder(clientVersion).apply(configure).build()
    }
}
