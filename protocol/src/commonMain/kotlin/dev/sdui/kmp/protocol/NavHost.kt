package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Kind of container chrome a [NavHost] provides. */
@Serializable
public enum class NavKind {
    Stack,
    Tab,
    BottomSheet,

    /**
     * Neutral fallback used when a newer server sends a [NavKind] this client does not
     * recognize. `SduiJson` coerces the unknown case to this value (via `coerceInputValues`)
     * instead of throwing; renderers treat it like [Stack]. Appended last so the additive
     * ordinal contract holds — never reordered.
     */
    Unspecified,
}

/**
 * A navigation container inside a server-driven tree. M4 ships the protocol surface; the
 * renderer (with tab chrome + bottom-sheet presentation) arrives in a later milestone. Until
 * then an `UnknownUiNode` fallback applies — clients should ship a [fallback] for older
 * renderers that do not support NavHost.
 *
 * [routes] maps an in-protocol route name to the absolute server path the client fetches
 * when that tab / destination becomes active.
 */
@Serializable
@SerialName("nav_host")
public data class NavHost(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val kind: NavKind = NavKind.Unspecified,
    public val initial: Destination,
    public val routes: Map<String, String> = emptyMap(),
    public val a11y: A11y? = null,
) : Leaf
