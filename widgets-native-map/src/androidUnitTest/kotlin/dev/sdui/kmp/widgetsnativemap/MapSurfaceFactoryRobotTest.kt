package dev.sdui.kmp.widgetsnativemap

import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.runtime.NativeSurfaceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Light-weight smoke test: instantiate the Android [MapSurfaceFactory], register it in a
 * [NativeSurfaceRegistry], and verify lookup by kind / version returns the same instance.
 *
 * No emulator, no Compose host — just the registry contract. The Composable
 * [MapSurfaceFactory.Render] would need an instrumentation test (Compose UI test or
 * Robolectric); deliberately deferred to a follow-up phase.
 */
class MapSurfaceFactoryRobotTest {

    @Test
    fun factory_registers_for_sdui_map_kind() {
        val factory = MapSurfaceFactory.instance(requireApiKey = false)
        assertEquals(MapSurfaceKind.ID, factory.kind)
        assertEquals(MapSurfaceKind.HandledVersions, factory.handledVersions)

        val registry = NativeSurfaceRegistry.build(clientVersion = SchemaVersion.V1) {
            register(factory)
        }

        val resolved = registry.factoryFor(kind = MapSurfaceKind.ID, nodeVersion = SchemaVersion.V1)
        assertNotNull(resolved)
        assertEquals(factory, resolved)
    }

    @Test
    fun factory_does_not_resolve_for_unknown_kind() {
        val factory = MapSurfaceFactory.instance(requireApiKey = false)
        val registry = NativeSurfaceRegistry.build(clientVersion = SchemaVersion.V1) {
            register(factory)
        }
        assertNull(registry.factoryFor(kind = "sdui.player", nodeVersion = SchemaVersion.V1))
    }
}
