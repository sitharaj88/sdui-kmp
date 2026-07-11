package dev.sdui.kmp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class TokensAndA11yTest {
    private val json: Json = SduiJson

    @Test
    fun color_tokens_each_have_stable_discriminator() {
        listOf<ColorToken>(
            ColorToken.Surface,
            ColorToken.OnSurface,
            ColorToken.Primary,
            ColorToken.OnPrimary,
            ColorToken.Error,
            ColorToken.Warning,
            ColorToken.Success,
            ColorToken.Muted,
        ).forEach { token ->
            val encoded = json.encodeToString(ColorToken.serializer(), token)
            assertEquals(token, json.decodeFromString(ColorToken.serializer(), encoded))
        }
    }

    @Test
    fun enum_tokens_roundtrip() {
        Spacing.entries.forEach {
            assertEquals(it, json.decodeFromString(Spacing.serializer(), json.encodeToString(Spacing.serializer(), it)))
        }
        TextStyleToken.entries.forEach {
            val s = TextStyleToken.serializer()
            assertEquals(it, json.decodeFromString(s, json.encodeToString(s, it)))
        }
        RadiusToken.entries.forEach {
            val s = RadiusToken.serializer()
            assertEquals(it, json.decodeFromString(s, json.encodeToString(s, it)))
        }
        ElevationToken.entries.forEach {
            val s = ElevationToken.serializer()
            assertEquals(it, json.decodeFromString(s, json.encodeToString(s, it)))
        }
    }

    @Test
    fun icon_token_named_roundtrips() {
        val token: IconToken = IconToken.Named("chevron_right")
        val encoded = json.encodeToString(IconToken.serializer(), token)
        assertEquals(token, json.decodeFromString(IconToken.serializer(), encoded))
    }

    @Test
    fun edge_insets_factories_behave() {
        val zero = EdgeInsets.Zero
        val all = EdgeInsets.all(Spacing.Md)
        val sym = EdgeInsets.symmetric(horizontal = Spacing.Lg, vertical = Spacing.Sm)
        assertEquals(Spacing.None, zero.top)
        assertEquals(Spacing.Md, all.start)
        assertEquals(Spacing.Lg, sym.start)
        assertEquals(Spacing.Sm, sym.top)

        val encoded = json.encodeToString(EdgeInsets.serializer(), all)
        assertEquals(all, json.decodeFromString(EdgeInsets.serializer(), encoded))
    }

    @Test
    fun a11y_full_and_empty_both_roundtrip() {
        val full = A11y(
            label = Value.ofString("Submit"),
            hint = Value.Bind(StatePath("hint")),
            role = A11yRole.Button,
            liveRegion = LiveRegion.Polite,
            isHidden = false,
            headingLevel = 2,
        )
        val encoded = json.encodeToString(A11y.serializer(), full)
        assertEquals(full, json.decodeFromString(A11y.serializer(), encoded))

        val empty = A11y()
        val emptyEncoded = json.encodeToString(A11y.serializer(), empty)
        assertEquals(empty, json.decodeFromString(A11y.serializer(), emptyEncoded))
    }

    @Test
    fun a11y_carries_through_widget_roundtrip() {
        val node: UiNode = Text(
            id = NodeId("t"),
            content = Value.ofString("hi"),
            a11y = A11y(label = Value.ofString("greeting"), role = A11yRole.Header, headingLevel = 1),
        )
        val encoded = json.encodeToString(UiNode.serializer(), node)
        assertEquals(node, json.decodeFromString(UiNode.serializer(), encoded))
    }
}
