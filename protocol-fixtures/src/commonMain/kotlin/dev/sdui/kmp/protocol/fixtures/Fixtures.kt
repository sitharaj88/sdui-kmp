package dev.sdui.kmp.protocol.fixtures

import dev.sdui.kmp.protocol.A11y
import dev.sdui.kmp.protocol.A11yRole
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.ColorToken
import dev.sdui.kmp.protocol.ContentScale
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.EdgeInsets
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.Keyboard
import dev.sdui.kmp.protocol.LazyList
import dev.sdui.kmp.protocol.ListSource
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.ScreenMetadata
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StateDeclaration
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.StateScope
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A single curated example of a protocol type plus the exact JSON it should emit.
 *
 * Fixtures power two contract tests: (1) every fixture round-trips byte-identical through
 * [dev.sdui.kmp.protocol.SduiJson], and (2) every fixture renders without falling back on
 * every platform. Adding a new widget to the protocol must add a fixture here — this is the
 * mechanism by which renderers and the server DSL can never silently skip new widgets.
 */
public data class NodeFixture(
    public val name: String,
    public val node: UiNode,
    public val json: String,
)

/** Corpus of node-level fixtures. */
public object NodeFixtures {
    public val minimalText: NodeFixture = NodeFixture(
        name = "minimal_text",
        node = Text(id = NodeId("t"), content = Value.ofString("hello")),
        json = """{"type":"text","id":"t","content":{"type":"literal","value":"hello"}}""",
    )

    public val textWithStyleAndColor: NodeFixture = NodeFixture(
        name = "text_heading_primary",
        node = Text(
            id = NodeId("title"),
            content = Value.ofString("Welcome"),
            style = TextStyleToken.Heading,
            color = ColorToken.Primary,
        ),
        json = """{"type":"text","id":"title","content":{"type":"literal","value":"Welcome"},"style":"Heading","color":{"type":"primary"}}""",
    )

    public val boundText: NodeFixture = NodeFixture(
        name = "bound_text",
        node = Text(id = NodeId("name"), content = Value.Bind(StatePath("user.name"))),
        json = """{"type":"text","id":"name","content":{"type":"bind","path":"user.name"}}""",
    )

    public val primaryButton: NodeFixture = NodeFixture(
        name = "primary_button_navigate",
        node = Button(
            id = NodeId("cta"),
            label = Value.ofString("Go"),
            action = Action.Navigate(Destination.ScreenDest("/about")),
            a11y = A11y(role = A11yRole.Button, label = Value.ofString("Go to About")),
        ),
        json = """{"type":"button","id":"cta","label":{"type":"literal","value":"Go"},"action":{"type":"navigate","destination":{"type":"screen","route":"/about"}},"a11y":{"label":{"type":"literal","value":"Go to About"},"role":"Button"}}""",
    )

    public val destructiveButton: NodeFixture = NodeFixture(
        name = "destructive_button_back",
        node = Button(
            id = NodeId("delete"),
            label = Value.ofString("Delete"),
            action = Action.Navigate(Destination.Back(count = 1)),
            style = ButtonStyle.Destructive,
        ),
        json = """{"type":"button","id":"delete","label":{"type":"literal","value":"Delete"},"action":{"type":"navigate","destination":{"type":"back"}},"style":"Destructive"}""",
    )

    public val columnWithSpacing: NodeFixture = NodeFixture(
        name = "column_with_spacing",
        node = Column(
            id = NodeId("root"),
            spacing = Spacing.Md,
            padding = EdgeInsets.all(Spacing.Sm),
            children = listOf(
                Text(id = NodeId("t1"), content = Value.ofString("one")),
                Text(id = NodeId("t2"), content = Value.ofString("two")),
            ),
        ),
        json = """{"type":"column","id":"root","children":[{"type":"text","id":"t1","content":{"type":"literal","value":"one"}},{"type":"text","id":"t2","content":{"type":"literal","value":"two"}}],"spacing":"Md","padding":{"top":"Sm","start":"Sm","end":"Sm","bottom":"Sm"}}""",
    )

    public val textWithTemplate: NodeFixture = NodeFixture(
        name = "text_with_template",
        node = Text(
            id = NodeId("greeting"),
            content = Value.template(
                pattern = "Hello {name}",
                bindings = mapOf("name" to StatePath("user.name")),
            ),
        ),
        json = """{"type":"text","id":"greeting","content":{"type":"template","pattern":"Hello {name}","bindings":{"name":"user.name"}}}""",
    )

    public val textFieldWithValidation: NodeFixture = NodeFixture(
        name = "text_field_email_with_validation",
        node = TextField(
            id = NodeId("email"),
            path = StatePath("login.email"),
            placeholder = Value.ofString("Email"),
            keyboard = Keyboard.Email,
            validation = Validation.All(
                validations = listOf(Validation.Required(), Validation.Email()),
            ),
        ),
        json = """{"type":"text_field","id":"email","path":"login.email","placeholder":{"type":"literal","value":"Email"},"keyboard":"Email","validation":{"type":"all","validations":[{"type":"required"},{"type":"email"}]}}""",
    )

    public val checkboxWithLabel: NodeFixture = NodeFixture(
        name = "checkbox_remember_me",
        node = Checkbox(
            id = NodeId("remember"),
            path = StatePath("login.remember"),
            label = Value.ofString("Remember me"),
        ),
        json = """{"type":"checkbox","id":"remember","path":"login.remember","label":{"type":"literal","value":"Remember me"}}""",
    )

    public val lazyListInline: NodeFixture = NodeFixture(
        name = "lazy_list_inline",
        node = LazyList(
            id = NodeId("feed"),
            source = ListSource.Inline(
                items = listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive("a"))
                        put("title", JsonPrimitive("Alpha"))
                    },
                    buildJsonObject {
                        put("id", JsonPrimitive("b"))
                        put("title", JsonPrimitive("Beta"))
                    },
                ),
            ),
            itemTemplate = Text(id = NodeId("row"), content = Value.Bind(StatePath("title"))),
            itemKeyPath = StatePath("id"),
            spacing = Spacing.Sm,
        ),
        json = """{"type":"lazy_list","id":"feed","source":{"type":"inline","items":[{"id":"a","title":"Alpha"},{"id":"b","title":"Beta"}]},"itemTemplate":{"type":"text","id":"row","content":{"type":"bind","path":"title"}},"itemKeyPath":"id","spacing":"Sm"}""",
    )

    public val lazyListBound: NodeFixture = NodeFixture(
        name = "lazy_list_bound",
        node = LazyList(
            id = NodeId("feed"),
            source = ListSource.Bound(path = StatePath("feed.items")),
            itemTemplate = Text(id = NodeId("row"), content = Value.Bind(StatePath("title"))),
            itemKeyPath = StatePath("id"),
        ),
        json = """{"type":"lazy_list","id":"feed","source":{"type":"bound","path":"feed.items"},"itemTemplate":{"type":"text","id":"row","content":{"type":"bind","path":"title"}},"itemKeyPath":"id"}""",
    )

    public val imageCrop: NodeFixture = NodeFixture(
        name = "image_hero_crop",
        node = Image(
            id = NodeId("hero"),
            source = Value.ofString("asset://hero.png"),
            contentDescription = Value.ofString("Hero banner"),
            contentScale = ContentScale.Crop,
        ),
        json = """{"type":"image","id":"hero","source":{"type":"literal","value":"asset://hero.png"},"contentDescription":{"type":"literal","value":"Hero banner"},"contentScale":"Crop"}""",
    )

    public val asyncImageBound: NodeFixture = NodeFixture(
        name = "async_image_bound_url",
        node = AsyncImage(
            id = NodeId("avatar"),
            url = Value.Bind(StatePath("driver.avatar")),
            contentDescription = Value.ofString("Driver"),
        ),
        json = """{"type":"async_image","id":"avatar","url":{"type":"bind","path":"driver.avatar"},"contentDescription":{"type":"literal","value":"Driver"}}""",
    )

    public val mapNativeSurface: NodeFixture = NodeFixture(
        name = "native_surface_sdui_map",
        node = NativeSurface(
            id = NodeId("map"),
            kind = "sdui.map",
            config = buildJsonObject {
                put("zoom", JsonPrimitive(13))
            },
            bindings = mapOf("driver_position" to StatePath("driver.location")),
        ),
        json = """{"type":"native","id":"map","kind":"sdui.map","config":{"zoom":13},"bindings":{"driver_position":"driver.location"}}""",
    )

    /** All node fixtures. New entries must be added here or the `all_fixtures_*` tests will not cover them. */
    public val all: List<NodeFixture> = listOf(
        minimalText,
        textWithStyleAndColor,
        boundText,
        primaryButton,
        destructiveButton,
        columnWithSpacing,
        textWithTemplate,
        textFieldWithValidation,
        checkboxWithLabel,
        lazyListInline,
        lazyListBound,
        imageCrop,
        asyncImageBound,
        mapNativeSurface,
    )
}

/** A full-screen fixture exercising metadata, state declarations, and a multi-child root. */
public data class ScreenFixture(
    public val name: String,
    public val screen: Screen,
    public val json: String,
)

public object ScreenFixtures {
    public val home: ScreenFixture = ScreenFixture(
        name = "home",
        screen = Screen(
            id = ScreenId("home"),
            version = SchemaVersion.V1,
            root = Column(
                id = NodeId("home/root"),
                spacing = Spacing.Md,
                padding = EdgeInsets.all(Spacing.Lg),
                children = listOf(
                    Text(
                        id = NodeId("home/title"),
                        content = Value.ofString("Welcome"),
                        style = TextStyleToken.Heading,
                    ),
                    Text(
                        id = NodeId("home/greeting"),
                        content = Value.Bind(StatePath("user.name")),
                    ),
                    Button(
                        id = NodeId("home/cta"),
                        label = Value.ofString("Go"),
                        action = Action.Navigate(Destination.ScreenDest("/about")),
                    ),
                ),
            ),
            stateDeclarations = listOf(
                StateDeclaration(
                    path = StatePath("user.name"),
                    scope = StateScope.Screen,
                    initial = JsonPrimitive("friend"),
                ),
            ),
            initialState = mapOf(StatePath("user.name") to JsonPrimitive("friend")),
            metadata = ScreenMetadata(
                title = Value.ofString("Home"),
                analyticsName = "home_screen",
                cacheTtlSeconds = 60L,
            ),
        ),
        json = """{"id":"home","version":1,"root":{"type":"column","id":"home/root","children":[{"type":"text","id":"home/title","content":{"type":"literal","value":"Welcome"},"style":"Heading"},{"type":"text","id":"home/greeting","content":{"type":"bind","path":"user.name"}},{"type":"button","id":"home/cta","label":{"type":"literal","value":"Go"},"action":{"type":"navigate","destination":{"type":"screen","route":"/about"}}}],"spacing":"Md","padding":{"top":"Lg","start":"Lg","end":"Lg","bottom":"Lg"}},"stateDeclarations":[{"path":"user.name","scope":"Screen","initial":"friend"}],"initialState":{"user.name":"friend"},"metadata":{"title":{"type":"literal","value":"Home"},"analyticsName":"home_screen","cacheTtlSeconds":60}}""",
    )

    public val all: List<ScreenFixture> = listOf(home)
}

/**
 * A fixture whose JSON carries a `type` discriminator unknown to the client.
 * Contract: decoding produces an `UnknownUiNode` with `originalType` equal to the emitted
 * discriminator, not a thrown exception.
 */
public object ForwardCompatFixtures {
    public const val UNKNOWN_WITH_FALLBACK: String = """
        {
          "type": "radical_new_widget",
          "id": "x1",
          "since": 42,
          "fallback": { "type": "text", "id": "x1.fallback", "content": { "type": "literal", "value": "older clients see me" } }
        }
    """

    public const val UNKNOWN_WITHOUT_FALLBACK: String = """{"type":"something","id":"q"}"""
}

/** Tree-patch and live-event wire fixtures covering the M5 transport surface. */
public object PatchAndLiveFixtures {
    public const val TREE_PATCH_REPLACE_APPEND_REMOVE: String =
        """{"ops":[{"type":"replace","nodeId":"t","node":{"type":"text","id":"t","content":{"type":"literal","value":"new"}}},{"type":"append","parentId":"root","nodes":[{"type":"text","id":"row","content":{"type":"literal","value":"row"}}]},{"type":"remove","nodeIds":["gone"]}]}"""

    public const val LIVE_STATE_UPDATE: String =
        """{"type":"state_update","updates":{"ticker":42,"message":"hello"}}"""

    public const val LIVE_TREE_PATCH: String =
        """{"type":"tree_patch","patch":{"ops":[{"type":"remove","nodeIds":["x"]}]}}"""
}
