package com.github.xepozz.introspectorplugin.core.platform

import com.github.xepozz.introspectorplugin.core.PluginInventory
import com.intellij.testFramework.LightPlatformTestCase
import org.junit.Assume.assumeTrue

/**
 * Platform-level smoke tests for [PluginInventory].
 *
 * Verifies that the application-level service can collect a real snapshot from the running
 * sandbox IDE: plugins listing is non-empty, includes platform plugins, EP listing works,
 * the TTL cache returns identical references within the window, and [PluginInventory.refresh]
 * produces a brand-new snapshot. Also exercises [PluginInventory.extensionsForEpLive] caching
 * against whatever EP happens to come first in the sandbox (EP names vary between IDE builds,
 * so we cannot hard-code an EP id here).
 *
 * Each test starts from a forced refresh so behaviour is independent of run order.
 */
class PluginInventoryPlatformTest : LightPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PluginInventory.getInstance().refresh()
    }

    fun testPluginsListIsNonEmpty() {
        val plugins = PluginInventory.getInstance().plugins()
        assertFalse("plugins() must return at least one entry in the test IDE", plugins.isEmpty())
    }

    fun testPluginsIncludesIntellijPlatform() {
        val plugins = PluginInventory.getInstance().plugins()
        val hasPlatform = plugins.any { it.id.startsWith("com.intellij") }
        assertTrue(
            "Expected at least one plugin id starting with 'com.intellij' (platform plugin); got ${plugins.map { it.id }}",
            hasPlatform,
        )
    }

    fun testExtensionPointsListNonEmpty() {
        val eps = PluginInventory.getInstance().extensionPoints()
        assertFalse("extensionPoints() must return at least one EP in the test IDE", eps.isEmpty())
    }

    fun testSnapshotIsCachedWithinTtl() {
        val inventory = PluginInventory.getInstance()
        val first = inventory.snapshot()
        val second = inventory.snapshot()
        assertSame(
            "Two consecutive snapshot() calls must return the same cached instance within TTL",
            first,
            second,
        )
    }

    fun testRefreshProducesFreshSnapshot() {
        val inventory = PluginInventory.getInstance()
        val before = inventory.snapshot()
        inventory.refresh()
        val after = inventory.snapshot()
        assertNotSame(
            "refresh() must invalidate the cache so subsequent snapshot() returns a new instance",
            before,
            after,
        )
    }

    fun testExtensionsForEpLiveCachesPerEp() {
        val inventory = PluginInventory.getInstance()
        // Find a real EP that actually has extensions. EPs with extensionsCount == 0 follow a
        // different cache path (the empty list never satisfies !isNullOrEmpty()), so the cache
        // hit assertion below would not be meaningful for them.
        val eps = inventory.extensionPoints()
        assumeTrue("Need at least one EP to test live extensions caching", eps.isNotEmpty())
        val populated = eps.firstOrNull { it.extensionsCount > 0 } ?: eps.first()
        val epName = populated.name

        val first = inventory.extensionsForEpLive(epName)
        val second = inventory.extensionsForEpLive(epName)

        // extensionsForEpLive returns `cached.take(limit)`, which is always a new list — so we
        // cannot directly assertSame on the returned slices. Instead, validate caching by
        // inspecting the underlying snapshot map: after the first call, the EP entry is
        // populated, and the second call must return a list with identical contents.
        assertEquals(
            "extensionsForEpLive($epName) must return identical contents across calls",
            first,
            second,
        )
        val cachedInSnapshot = inventory.snapshot().extensionsByEp[epName]
        assertNotNull("Snapshot must keep a cache entry for $epName after extensionsForEpLive", cachedInSnapshot)
    }
}
