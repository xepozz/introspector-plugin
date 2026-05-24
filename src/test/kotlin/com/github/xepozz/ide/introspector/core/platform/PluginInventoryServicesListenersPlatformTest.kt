package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.PluginInventory
import com.intellij.testFramework.LightPlatformTestCase
import org.junit.Assume.assumeTrue

/**
 * Platform-level smoke tests for the services/listeners surface of [PluginInventory].
 *
 * Verifies that the snapshot collects the new sections, that the per-plugin getters agree with
 * the global lists, and that `lightInstantiatedServices()` is cached separately on its own TTL.
 */
class PluginInventoryServicesListenersPlatformTest : LightPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PluginInventory.getInstance().refresh()
    }

    fun testSnapshotIncludesServicesByPluginId() {
        val s = PluginInventory.getInstance().snapshot()
        assertNotNull("Snapshot.servicesByPluginId must be populated", s.servicesByPluginId)
        assertFalse(
            "Snapshot.servicesByPluginId must have at least one plugin id with services",
            s.servicesByPluginId.isEmpty(),
        )
    }

    fun testServicesFlatViewMatchesPerPluginAggregation() {
        val inv = PluginInventory.getInstance()
        val flat = inv.services()
        val aggregated = inv.snapshot().servicesByPluginId.values.flatten()
        assertEquals(
            "PluginInventory.services() must equal the flat aggregation of servicesByPluginId",
            aggregated.size,
            flat.size,
        )
    }

    fun testServicesByPluginAgreesWithSnapshot() {
        val inv = PluginInventory.getInstance()
        val snapshot = inv.snapshot()
        val anyPluginWithServices = snapshot.servicesByPluginId
            .entries.firstOrNull { it.value.isNotEmpty() }
        assumeTrue("Need at least one plugin with services to assert", anyPluginWithServices != null)
        val (pid, expected) = anyPluginWithServices!!
        val viaGetter = inv.servicesByPlugin(pid)
        assertEquals("servicesByPlugin($pid) must agree with snapshot", expected, viaGetter)
    }

    fun testListenersByPluginAgreesWithSnapshot() {
        val inv = PluginInventory.getInstance()
        val snapshot = inv.snapshot()
        val anyPluginWithListeners = snapshot.listenersByPluginId
            .entries.firstOrNull { it.value.isNotEmpty() }
        assumeTrue("Need at least one plugin with listeners to assert", anyPluginWithListeners != null)
        val (pid, expected) = anyPluginWithListeners!!
        val viaGetter = inv.listenersByPlugin(pid)
        assertEquals("listenersByPlugin($pid) must agree with snapshot", expected, viaGetter)
    }

    fun testServicesCountInPluginInfoMatchesActualCount() {
        val inv = PluginInventory.getInstance()
        val plugin = inv.plugins().firstOrNull { it.servicesCount > 0 }
        assumeTrue("Need at least one plugin with servicesCount > 0", plugin != null)
        val actual = inv.servicesByPlugin(plugin!!.id).size
        assertEquals(
            "PluginInfo.servicesCount must match servicesByPlugin(${plugin.id}).size",
            plugin.servicesCount,
            actual,
        )
    }

    fun testListenersCountInPluginInfoMatchesActualCount() {
        val inv = PluginInventory.getInstance()
        val plugin = inv.plugins().firstOrNull { it.listenersCount > 0 }
        assumeTrue("Need at least one plugin with listenersCount > 0", plugin != null)
        val actual = inv.listenersByPlugin(plugin!!.id).size
        assertEquals(
            "PluginInfo.listenersCount must match listenersByPlugin(${plugin.id}).size",
            plugin.listenersCount,
            actual,
        )
    }

    fun testUnknownPluginIdReturnsEmptyServiceList() {
        val result = PluginInventory.getInstance().servicesByPlugin("com.example.does.not.exist")
        assertTrue(
            "Unknown plugin must produce empty servicesByPlugin, got ${result.size}",
            result.isEmpty(),
        )
    }

    fun testUnknownPluginIdReturnsEmptyListenerList() {
        val result = PluginInventory.getInstance().listenersByPlugin("com.example.does.not.exist")
        assertTrue(
            "Unknown plugin must produce empty listenersByPlugin, got ${result.size}",
            result.isEmpty(),
        )
    }

    fun testLightInstantiatedServicesIsCachedSeparately() {
        val inv = PluginInventory.getInstance()
        val first = inv.lightInstantiatedServices()
        val second = inv.lightInstantiatedServices()
        // The TtlCache returns the same value reference within the TTL window.
        assertSame(
            "lightInstantiatedServices() must reuse the cached list within its TTL window",
            first,
            second,
        )
    }

    fun testRefreshInvalidatesLightServiceCache() {
        val inv = PluginInventory.getInstance()
        val before = inv.lightInstantiatedServices()
        inv.refresh()
        val after = inv.lightInstantiatedServices()
        assertNotSame(
            "refresh() must invalidate the light-service cache so a subsequent call returns a fresh list",
            before,
            after,
        )
    }
}
