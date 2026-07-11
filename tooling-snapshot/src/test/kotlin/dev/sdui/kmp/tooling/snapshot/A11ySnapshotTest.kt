package dev.sdui.kmp.tooling.snapshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import dev.sdui.kmp.protocol.A11y
import dev.sdui.kmp.protocol.A11yRole
import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Button
import dev.sdui.kmp.protocol.ButtonStyle
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.Image
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.Spacing
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TextField
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.LocalActionDispatcher
import dev.sdui.kmp.runtime.LocalRegistry
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.LocalTelemetry
import dev.sdui.kmp.runtime.NoopTelemetry
import dev.sdui.kmp.runtime.StateStore
import dev.sdui.kmp.runtime.WidgetRegistry
import dev.sdui.kmp.tooling.testing.assertHasContentDescription
import dev.sdui.kmp.tooling.testing.assertHasRole
import dev.sdui.kmp.tooling.testing.assertSatisfiesTouchTarget
import dev.sdui.kmp.widgetscore.ButtonRenderer
import dev.sdui.kmp.widgetscore.ColumnRenderer
import dev.sdui.kmp.widgetscore.TextRenderer
import dev.sdui.kmp.widgetsforms.CheckboxRenderer
import dev.sdui.kmp.widgetsforms.TextFieldRenderer
import dev.sdui.kmp.widgetsmedia.ImageRenderer
import kotlin.test.Test

/**
 * Accessibility snapshot suite. One test per widget × visually-distinct semantic variant.
 *
 * Where [WidgetSnapshotTest] guards visual regressions via PNG diff, this suite guards the
 * *semantic* tree — content descriptions, roles, touch-target sizes — using Compose's
 * ui-test infrastructure plus the [dev.sdui.kmp.tooling.testing.A11yAssertions] helpers.
 *
 * The two suites are intentionally separate because they fail differently: a pixel diff
 * means a visual regression, an a11y diff means a screen-reader regression. CI surfaces
 * each kind with its own failure message.
 */
@OptIn(ExperimentalTestApi::class)
class A11ySnapshotTest {

    private val registry: WidgetRegistry = WidgetRegistry.build {
        register(TextRenderer)
        register(ButtonRenderer)
        register(ColumnRenderer)
        register(TextFieldRenderer)
        register(CheckboxRenderer)
        register(ImageRenderer)
    }

    @Test
    fun text_with_a11y_label_exposes_content_description() = runComposeUiTest {
        setContent {
            ProvideRuntime {
                TextRenderer.Render(
                    node = Text(
                        id = NodeId("t-a11y"),
                        since = SchemaVersion.V1,
                        content = Value.ofString("Welcome back"),
                        a11y = A11y(label = Value.ofString("Personalised greeting")),
                    ),
                    modifier = Modifier,
                )
            }
        }
        firstNodeWithContentDescription("Personalised greeting").assertHasContentDescription()
    }

    @Test
    fun button_has_role_and_touch_target() = runComposeUiTest {
        setContent {
            ProvideRuntime {
                ButtonRenderer.Render(
                    node = Button(
                        id = NodeId("b-primary"),
                        since = SchemaVersion.V1,
                        label = Value.ofString("Continue"),
                        action = Action.Navigate(Destination.ScreenDest(route = "next")),
                        style = ButtonStyle.Primary,
                    ),
                    modifier = Modifier,
                )
            }
        }
        firstNodeWithRole(Role.Button)
            .assertHasRole(Role.Button)
            .assertSatisfiesTouchTarget()
    }

    @Test
    fun image_with_content_description_announces_alt_text() = runComposeUiTest {
        setContent {
            ProvideRuntime {
                ImageRenderer.Render(
                    node = Image(
                        id = NodeId("img-hero"),
                        since = SchemaVersion.V1,
                        source = Value.ofString("hero-banner.png"),
                        contentDescription = Value.ofString("Tropical beach at sunrise"),
                    ),
                    modifier = Modifier,
                )
            }
        }
        firstNodeWithContentDescription("Tropical beach at sunrise").assertHasContentDescription()
    }

    @Test
    fun text_field_with_placeholder_falls_back_to_content_description() = runComposeUiTest {
        setContent {
            ProvideRuntime {
                TextFieldRenderer.Render(
                    node = TextField(
                        id = NodeId("tf-email"),
                        since = SchemaVersion.V1,
                        path = StatePath("form.email"),
                        placeholder = Value.ofString("you@example.com"),
                    ),
                    modifier = Modifier,
                )
            }
        }
        // No `a11y.label` was supplied, so the renderer projects the placeholder onto
        // contentDescription as the fallback accessible name.
        firstNodeWithContentDescription("you@example.com").assertHasContentDescription()
    }

    @Test
    fun stack_of_widgets_each_has_required_semantics() = runComposeUiTest {
        setContent {
            ProvideRuntime {
                ColumnRenderer.Render(
                    node = Column(
                        id = NodeId("col"),
                        since = SchemaVersion.V1,
                        spacing = Spacing.Md,
                        children = listOf(
                            Text(
                                id = NodeId("t-1"),
                                since = SchemaVersion.V1,
                                content = Value.ofString("Heading"),
                                a11y = A11y(headingLevel = 1, role = A11yRole.Header),
                            ),
                            Button(
                                id = NodeId("b-1"),
                                since = SchemaVersion.V1,
                                label = Value.ofString("Submit"),
                                action = Action.Navigate(Destination.ScreenDest(route = "thanks")),
                            ),
                        ),
                    ),
                    modifier = Modifier,
                )
            }
        }
        // The button inside the column still meets the role + touch-target invariants when
        // it's nested in a Column.
        firstNodeWithRole(Role.Button)
            .assertHasRole(Role.Button)
            .assertSatisfiesTouchTarget()
    }

    // --- helpers ---------------------------------------------------------------------------

    @Composable
    private fun ProvideRuntime(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRegistry provides registry,
            LocalStateStore provides StateStore.Empty,
            LocalActionDispatcher provides NoopActionDispatcher,
            LocalTelemetry provides NoopTelemetry,
        ) { content() }
    }

    /**
     * Picks the first node in the merged semantic tree carrying the supplied
     * [SemanticsProperties.ContentDescription]. Wraps Compose's [onAllNodes] +
     * [SemanticsMatcher.expectValue] pair so test methods stay readable.
     */
    private fun ComposeUiTest.firstNodeWithContentDescription(value: String) =
        onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(value),
            ),
        )[0]

    /**
     * Picks the first node in the merged semantic tree whose [SemanticsProperties.Role]
     * matches [role].
     */
    private fun ComposeUiTest.firstNodeWithRole(role: Role) =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))[0]
}
