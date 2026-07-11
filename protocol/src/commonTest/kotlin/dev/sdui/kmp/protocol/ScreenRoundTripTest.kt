package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class ScreenRoundTripTest {
    private val json: Json = SduiJson

    @Test
    fun full_screen_roundtrips_byte_identical() {
        val screen = Screen(
            id = ScreenId("home"),
            version = SchemaVersion.V1,
            root = Column(
                id = NodeId("root"),
                spacing = Spacing.Md,
                padding = EdgeInsets.all(Spacing.Lg),
                children = listOf(
                    Text(id = NodeId("title"), content = Value.ofString("Welcome"), style = TextStyleToken.Heading),
                    Text(id = NodeId("sub"), content = Value.Bind(StatePath("user.name"))),
                    Button(
                        id = NodeId("cta"),
                        label = Value.ofString("Go"),
                        action = Action.Navigate(Destination.ScreenDest("/about")),
                        a11y = A11y(role = A11yRole.Button, label = Value.ofString("Go to about")),
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
        )
        val first = json.encodeToString(Screen.serializer(), screen)
        val decoded = json.decodeFromString(Screen.serializer(), first)
        val second = json.encodeToString(Screen.serializer(), decoded)
        assertEquals(first, second, "re-encoded form must be byte-identical")
        assertEquals(screen, decoded)
    }
}
