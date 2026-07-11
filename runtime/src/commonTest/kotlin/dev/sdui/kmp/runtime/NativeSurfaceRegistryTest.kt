package dev.sdui.kmp.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sdui.kmp.protocol.NativeSurface
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.SchemaVersion
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private object StubMapFactory : NativeSurfaceFactory {
    override val kind: String = "sdui.map"
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion.V1
    @Composable override fun Render(surface: NativeSurface, modifier: Modifier) {}
}

class NativeSurfaceRegistryTest {

    @Test
    fun registered_kind_resolves() {
        val registry = NativeSurfaceRegistry.build {
            register(StubMapFactory)
        }
        val node = NativeSurface(id = NodeId("m"), kind = "sdui.map")
        assertNotNull(registry.factoryFor(node.kind, node.since))
    }

    @Test
    fun unregistered_kind_returns_null() {
        val registry = NativeSurfaceRegistry.build {
            register(StubMapFactory)
        }
        assertNull(registry.factoryFor("sdui.unheard_of", SchemaVersion.V1))
    }

    @Test
    fun client_newer_than_factory_range_returns_null() {
        val registry = NativeSurfaceRegistry.Builder(clientVersion = SchemaVersion(99))
            .register(StubMapFactory)
            .build()
        assertNull(registry.factoryFor("sdui.map", SchemaVersion.V1))
    }

    @Test
    fun empty_registry_resolves_nothing() {
        assertNull(NativeSurfaceRegistry.Empty.factoryFor("sdui.map", SchemaVersion.V1))
    }
}
