package dev.sdui.kmp.protocol.property

import dev.sdui.kmp.protocol.A11y
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.ContentScale
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import kotlinx.serialization.json.JsonPrimitive

/**
 * Arbitraries for the protocol types covered by the property suite.
 *
 * Scope is intentionally focused on the M0–M2 surface — `Column`, `Text`, `Button`, `Image`,
 * `AsyncImage` — plus their `Value<T>` and `Action` payloads. The exhaustive sealed hierarchy
 * (LazyList, NavHost, NativeSurface, FormWidgets, …) is documented as a follow-up in
 * `docs/adr/0014-property-based-protocol-tests.md`.
 *
 * Everything is internal: these factories are test-only and not part of the protocol's
 * `explicitApi()` contract.
 */

internal const val MAX_TREE_DEPTH: Int = 4

// --- Leaf-level building blocks ----------------------------------------------------------------

internal val arbNodeId: Arb<NodeId> =
    Arb.string(minSize = 1, maxSize = 12).map { NodeId(it.replace(Regex("[^A-Za-z0-9_-]"), "_")) }

internal val arbSchemaVersion: Arb<SchemaVersion> = Arb.int(min = 1, max = 64).map(::SchemaVersion)

internal val arbStatePath: Arb<StatePath> =
    Arb.list(Arb.string(minSize = 1, maxSize = 6), 1..3).map { segments ->
        StatePath(segments.joinToString(".") { it.replace(".", "_") })
    }

internal val arbSpacing: Arb<Spacing> = Arb.enum<Spacing>()
internal val arbTextStyleToken: Arb<TextStyleToken> = Arb.enum<TextStyleToken>()
internal val arbButtonStyle: Arb<ButtonStyle> = Arb.enum<ButtonStyle>()
internal val arbContentScale: Arb<ContentScale> = Arb.enum<ContentScale>()

internal val arbColorToken: Arb<ColorToken> = Arb.element(
    ColorToken.Surface,
    ColorToken.OnSurface,
    ColorToken.Primary,
    ColorToken.OnPrimary,
    ColorToken.Error,
    ColorToken.Warning,
    ColorToken.Success,
    ColorToken.Muted,
)

internal val arbEdgeInsets: Arb<EdgeInsets> = Arb.bind(
    arbSpacing, arbSpacing, arbSpacing, arbSpacing,
) { top, start, end, bottom ->
    EdgeInsets(top = top, start = start, end = end, bottom = bottom)
}

internal val arbA11y: Arb<A11y?> = Arb.string(maxSize = 16).orNull(0.5).map { label ->
    label?.let { A11y(label = Value.ofString(it)) }
}

// --- Value<T> arbs -----------------------------------------------------------------------------

internal val arbStringLiteral: Arb<Value<String>> =
    Arb.string(maxSize = 24).map { Value.ofString(it) }

internal val arbIntLiteral: Arb<Value<Int>> =
    Arb.int().map { Value.ofInt(it) }

internal val arbBooleanLiteral: Arb<Value<Boolean>> =
    Arb.boolean().map { Value.ofBoolean(it) }

internal val arbStringBind: Arb<Value<String>> = arbStatePath.map { Value.Bind(it) }

internal val arbStringTemplate: Arb<Value<String>> = Arb.bind(
    Arb.string(minSize = 1, maxSize = 12).map { it.replace(Regex("[{}]"), "_") },
    Arb.map(
        Arb.string(minSize = 1, maxSize = 4).map { it.replace(Regex("[{}.]"), "_") },
        arbStatePath,
        minSize = 0,
        maxSize = 3,
    ),
) { pattern, bindings -> Value.template(pattern, bindings) }

internal val arbStringValue: Arb<Value<String>> = Arb.choice(
    arbStringLiteral,
    arbStringBind,
    arbStringTemplate,
)

// --- Destination + Action arbs -----------------------------------------------------------------

internal val arbDestination: Arb<Destination> = Arb.choice(
    Arb.string(minSize = 1, maxSize = 12).map { Destination.ScreenDest("/$it") },
    Arb.constant(Destination.Back()),
    Arb.string(minSize = 1, maxSize = 12).map { Destination.ScreenDest(it) },
)

internal val arbAction: Arb<Action> = Arb.choice(
    Arb.bind(arbDestination, Arb.boolean()) { dest, replace ->
        Action.Navigate(destination = dest, replace = replace)
    },
    Arb.bind(arbStatePath, Arb.string(maxSize = 8)) { path, value ->
        Action.UpdateState(path = path, value = Value.Literal(JsonPrimitive(value)))
    },
)

// --- Leaf nodes --------------------------------------------------------------------------------

internal val arbText: Arb<Text> = Arb.bind(
    arbNodeId, arbSchemaVersion, arbStringValue, arbTextStyleToken, arbColorToken.orNull(0.5),
) { id, since, content, style, color ->
    Text(id = id, since = since, content = content, style = style, color = color, fallback = null)
}

internal val arbButton: Arb<Button> = Arb.bind(
    arbNodeId, arbSchemaVersion, arbStringValue, arbAction, arbButtonStyle,
) { id, since, label, action, style ->
    Button(id = id, since = since, label = label, action = action, style = style, fallback = null)
}

internal val arbImage: Arb<Image> = Arb.bind(
    arbNodeId, arbSchemaVersion, arbStringValue, arbStringValue.orNull(0.4), arbContentScale,
) { id, since, source, contentDescription, contentScale ->
    Image(
        id = id,
        since = since,
        source = source,
        contentDescription = contentDescription,
        contentScale = contentScale,
        fallback = null,
    )
}

internal val arbAsyncImageLeaf: Arb<AsyncImage> = Arb.bind(
    arbNodeId, arbSchemaVersion, arbStringValue, arbStringValue.orNull(0.4), arbContentScale,
) { id, since, url, contentDescription, contentScale ->
    AsyncImage(
        id = id,
        since = since,
        url = url,
        contentDescription = contentDescription,
        contentScale = contentScale,
        placeholder = null,
        error = null,
        fallback = null,
    )
}

private val arbLeafNode: Arb<UiNode> = Arb.choice(
    arbText.map { it as UiNode },
    arbButton.map { it as UiNode },
    arbImage.map { it as UiNode },
    arbAsyncImageLeaf.map { it as UiNode },
)

// --- Recursive Container arb -------------------------------------------------------------------

/**
 * Build a [UiNode] arb of bounded depth so generation can never blow the stack.
 *
 * `depth = 0` returns only leaves; each recursive level halves the branching probability so
 * deep, narrow trees and wide, shallow trees are both reachable.
 */
internal fun arbUiNode(depth: Int = MAX_TREE_DEPTH): Arb<UiNode> {
    if (depth <= 0) return arbLeafNode
    val child = arbUiNode(depth - 1)
    val column: Arb<UiNode> = Arb.bind(
        arbNodeId,
        arbSchemaVersion,
        Arb.list(child, 0..3),
        arbSpacing,
        arbEdgeInsets,
    ) { id, since, children, spacing, padding ->
        Column(
            id = id,
            since = since,
            fallback = null,
            children = children,
            spacing = spacing,
            padding = padding,
        )
    }
    return Arb.choice(arbLeafNode, column)
}

internal val arbContainer: Arb<Column> = Arb.bind(
    arbNodeId,
    arbSchemaVersion,
    Arb.list(arbUiNode(MAX_TREE_DEPTH - 1), 0..4),
    arbSpacing,
    arbEdgeInsets,
) { id, since, children, spacing, padding ->
    Column(
        id = id,
        since = since,
        fallback = null,
        children = children,
        spacing = spacing,
        padding = padding,
    )
}
