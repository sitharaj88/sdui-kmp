package dev.sdui.kmp.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Where a [StateDeclaration] lives in the scope tree. Protocol v0 defaults to [Global]. */
@Serializable
public enum class StateScope { Global, Screen, Node, Ephemeral }

/**
 * A declared piece of state that a screen depends on.
 *
 * Declaring state up-front lets the runtime initialize it before the first render and lets
 * the linter warn when a widget binds to a path that no declaration covers.
 */
@Serializable
public data class StateDeclaration(
    public val path: StatePath,
    public val scope: StateScope = StateScope.Global,
    public val initial: JsonElement,
    public val persist: Boolean = false,
)

/** Optional metadata the runtime surfaces to analytics and navigation. */
@Serializable
public data class ScreenMetadata(
    public val title: Value<String>? = null,
    public val analyticsName: String? = null,
    public val cacheTtlSeconds: Long? = null,
)

/**
 * Top-level envelope for a server-emitted UI tree.
 *
 * Round-trips byte-identically under [SduiJson] so caching, deltas, and contract tests can
 * compare full screens without normalization steps.
 */
@Serializable
public data class Screen(
    public val id: ScreenId,
    public val version: SchemaVersion,
    public val root: UiNode,
    public val stateDeclarations: List<StateDeclaration> = emptyList(),
    public val initialState: Map<StatePath, JsonElement> = emptyMap(),
    public val metadata: ScreenMetadata = ScreenMetadata(),
)
