package dev.sdui.kmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Where a [LazyList] gets its items. */
@Serializable
public sealed interface ListSource {
    /**
     * Items ship inline with the screen. Each element is a JSON object whose keys are
     * materialized into the per-item state scope, so the [LazyList.itemTemplate] can bind to
     * them directly (e.g. `Value.Bind(StatePath("title"))`).
     */
    @Serializable
    @SerialName("inline")
    public data class Inline(public val items: List<JsonObject>) : ListSource

    /**
     * Items come from a paged HTTP endpoint. Clients ask for consecutive pages; the server
     * returns `Pagination` envelopes. M4 defines the protocol; the HTTP pager lands alongside
     * transport hardening.
     */
    @Serializable
    @SerialName("paged")
    public data class Paged(
        public val endpoint: String,
        public val pageSize: Int = 20,
        public val cursor: String? = null,
    ) : ListSource

    /**
     * Items come from the state store. The value at [path] must decode as a JSON array of
     * objects; each element seeds a per-item state scope exactly like [Inline].
     */
    @Serializable
    @SerialName("bound")
    public data class Bound(public val path: StatePath) : ListSource

    /**
     * Inert sentinel decoded when the `type` discriminator names a [ListSource] this client does
     * not recognize. Renderers treat it as an empty source rather than throwing, so a newer list
     * source can never blank the screen on an older client (the server can still supply
     * [LazyList.emptyState]).
     *
     * Produced only by [SduiSerializersModule]'s polymorphic default deserializer; servers must
     * never emit it.
     */
    @Serializable
    @SerialName("__unknown__")
    public data class Unknown(
        /** The original `type` discriminator the client could not resolve. */
        public val originalType: String = "",
    ) : ListSource
}

/** Response envelope for a [ListSource.Paged] request. */
@Serializable
public data class Pagination(
    public val items: List<JsonObject>,
    public val nextCursor: String? = null,
)

/**
 * Virtualized list of items rendered from an [itemTemplate].
 *
 * Each visible item is rendered against a per-item state scope; bindings inside [itemTemplate]
 * resolve against that scope first and fall through to screen / global state on miss. The
 * protocol also ships optional [emptyState], [loadingState], [errorState] sub-trees so the
 * server controls presentation across all three states uniformly.
 */
@Serializable
@SerialName("lazy_list")
public data class LazyList(
    override val id: NodeId,
    override val since: SchemaVersion = SchemaVersion.V1,
    override val fallback: UiNode? = null,
    public val source: ListSource,
    public val itemTemplate: UiNode,
    public val itemKeyPath: StatePath,
    public val orientation: Orientation = Orientation.Vertical,
    public val spacing: Spacing = Spacing.None,
    public val padding: EdgeInsets = EdgeInsets.Zero,
    public val emptyState: UiNode? = null,
    public val loadingState: UiNode? = null,
    public val errorState: UiNode? = null,
    public val pullToRefresh: Boolean = false,
    public val a11y: A11y? = null,
) : Leaf
