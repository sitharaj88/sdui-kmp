package dev.sdui.kmp.tooling.snapshot

import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.AsyncImage
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.Checkbox
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.TextStyleToken
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.WidgetRegistry
import dev.sdui.kmp.widgetscore.ButtonRenderer
import dev.sdui.kmp.widgetscore.ColumnRenderer
import dev.sdui.kmp.widgetscore.TextRenderer
import dev.sdui.kmp.widgetsforms.CheckboxRenderer
import dev.sdui.kmp.widgetsforms.TextFieldRenderer
import dev.sdui.kmp.widgetsmedia.AsyncImageRenderer
import dev.sdui.kmp.widgetsmedia.ImageRenderer
import kotlin.test.Test

/**
 * Golden-snapshot suite. One test per widget × visually-distinct variant.
 *
 * Adding a new widget? Register its renderer below and write a `@Test fun foo_variant() = ...`
 * — then run `./gradlew :tooling-snapshot:recordGoldenSnapshots` once and commit the new PNG.
 *
 * Running locally:
 *   * verify (default): `./gradlew :tooling-snapshot:verifyGoldenSnapshots`
 *   * record:           `./gradlew :tooling-snapshot:recordGoldenSnapshots`
 */
class WidgetSnapshotTest {

    // Shared registry: every test snapshots a single widget but composing in isolation is fine —
    // the registry only matters for renderers that delegate to children (Column → Text/Button).
    private val registry: WidgetRegistry = WidgetRegistry.build {
        register(TextRenderer)
        register(ButtonRenderer)
        register(ColumnRenderer)
        register(TextFieldRenderer)
        register(CheckboxRenderer)
        register(ImageRenderer)
        register(AsyncImageRenderer)
    }

    private fun nav(route: String): Action.Navigate =
        Action.Navigate(Destination.ScreenDest(route = route))

    // ----- Text -------------------------------------------------------------------------------

    @Test
    fun text_heading() = snapshot("text_heading") {
        SnapshotScaffold(registry) {
            TextRenderer.Render(
                node = Text(
                    id = NodeId("t-heading"),
                    since = SchemaVersion.V1,
                    content = Value.ofString("Welcome back"),
                    style = TextStyleToken.Heading,
                ),
                modifier = Modifier,
            )
        }
    }

    @Test
    fun text_body() = snapshot("text_body") {
        SnapshotScaffold(registry) {
            TextRenderer.Render(
                node = Text(
                    id = NodeId("t-body"),
                    since = SchemaVersion.V1,
                    content = Value.ofString("Body copy renders with the M3 default body style."),
                    style = TextStyleToken.Body,
                ),
                modifier = Modifier,
            )
        }
    }

    // ----- Button -----------------------------------------------------------------------------

    @Test
    fun button_primary() = snapshot("button_primary") {
        SnapshotScaffold(registry) {
            ButtonRenderer.Render(
                node = Button(
                    id = NodeId("b-primary"),
                    since = SchemaVersion.V1,
                    label = Value.ofString("Continue"),
                    action = nav("next"),
                    style = ButtonStyle.Primary,
                ),
                modifier = Modifier,
            )
        }
    }

    @Test
    fun button_secondary() = snapshot("button_secondary") {
        SnapshotScaffold(registry) {
            ButtonRenderer.Render(
                node = Button(
                    id = NodeId("b-secondary"),
                    since = SchemaVersion.V1,
                    label = Value.ofString("Cancel"),
                    action = nav("home"),
                    style = ButtonStyle.Secondary,
                ),
                modifier = Modifier,
            )
        }
    }

    @Test
    fun button_destructive() = snapshot("button_destructive") {
        SnapshotScaffold(registry) {
            ButtonRenderer.Render(
                node = Button(
                    id = NodeId("b-destructive"),
                    since = SchemaVersion.V1,
                    label = Value.ofString("Delete account"),
                    action = nav("home"),
                    style = ButtonStyle.Destructive,
                ),
                modifier = Modifier,
            )
        }
    }

    // ----- Column -----------------------------------------------------------------------------

    @Test
    fun column_with_spacing() = snapshot("column_with_spacing") {
        SnapshotScaffold(registry) {
            ColumnRenderer.Render(
                node = Column(
                    id = NodeId("col"),
                    since = SchemaVersion.V1,
                    spacing = Spacing.Md,
                    children = listOf(
                        Text(
                            id = NodeId("c-1"),
                            since = SchemaVersion.V1,
                            content = Value.ofString("First row"),
                            style = TextStyleToken.Heading,
                        ),
                        Text(
                            id = NodeId("c-2"),
                            since = SchemaVersion.V1,
                            content = Value.ofString("Second row"),
                            style = TextStyleToken.Body,
                        ),
                        Text(
                            id = NodeId("c-3"),
                            since = SchemaVersion.V1,
                            content = Value.ofString("Third row"),
                            style = TextStyleToken.Caption,
                        ),
                    ),
                ),
                modifier = Modifier,
            )
        }
    }

    // ----- TextField --------------------------------------------------------------------------

    @Test
    fun text_field_empty() = snapshot("text_field_empty") {
        SnapshotScaffold(registry) {
            TextFieldRenderer.Render(
                node = TextField(
                    id = NodeId("tf"),
                    since = SchemaVersion.V1,
                    path = StatePath("form.email"),
                    placeholder = Value.ofString("you@example.com"),
                ),
                modifier = Modifier,
            )
        }
    }

    // ----- Checkbox ---------------------------------------------------------------------------

    @Test
    fun checkbox_unchecked() = snapshot("checkbox_unchecked") {
        SnapshotScaffold(registry) {
            CheckboxRenderer.Render(
                node = Checkbox(
                    id = NodeId("cb-off"),
                    since = SchemaVersion.V1,
                    path = StatePath("agree"),
                    label = Value.ofString("Accept terms"),
                ),
                modifier = Modifier,
            )
        }
    }

    @Test
    fun checkbox_checked() = snapshot("checkbox_checked") {
        // The empty StateStore in SnapshotScaffold reads `agree` as false. To snapshot the
        // *checked* visual we render a sibling test scaffold with a pre-seeded store via a
        // local widget composition — the existing renderer reads from LocalStateStore.
        SnapshotScaffold(registry) {
            CheckedCheckboxFixture()
        }
    }

    // ----- Image ------------------------------------------------------------------------------

    @Test
    fun image_placeholder() = snapshot("image_placeholder") {
        SnapshotScaffold(registry) {
            ImageRenderer.Render(
                node = Image(
                    id = NodeId("img"),
                    since = SchemaVersion.V1,
                    source = Value.ofString("hero-banner.png"),
                    contentDescription = Value.ofString("Hero banner"),
                ),
                modifier = Modifier,
            )
        }
    }

    // ----- AsyncImage -------------------------------------------------------------------------

    @Test
    fun async_image_placeholder() = snapshot("async_image_placeholder") {
        SnapshotScaffold(registry) {
            AsyncImageRenderer.Render(
                node = AsyncImage(
                    id = NodeId("aimg"),
                    since = SchemaVersion.V1,
                    url = Value.ofString("https://example.com/avatar.png"),
                    contentDescription = Value.ofString("User avatar"),
                ),
                modifier = Modifier,
            )
        }
    }
}
