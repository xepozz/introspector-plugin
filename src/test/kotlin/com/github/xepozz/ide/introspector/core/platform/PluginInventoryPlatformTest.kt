package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.PluginInventory
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

    // ====================================================================================
    // extensionsByPlugin — walks every EP and filters by providedByPluginId
    // ====================================================================================

    /**
     * Walk every EP via [PluginInventory.extensionsByPlugin] for a plugin that we know declares
     * extensions in the test IDE (any plugin with `declaredExtensionPointsCount > 0` is a good
     * candidate — declared EPs imply both that the plugin contributes and that other plugins
     * register extensions back into its EPs). We assert the per-plugin filter is honoured.
     *
     * Picking a plugin via `declaredExtensionPointsCount > 0` (rather than hard-coding
     * `com.intellij`) keeps the test resilient to IDE-edition differences.
     */
    fun testExtensionsByPluginReturnsExtensionsForKnownPlugin() {
        val inventory = PluginInventory.getInstance()
        val plugins = inventory.plugins()
        // Prefer a platform plugin (com.intellij*) that declares EPs — almost every test IDE
        // ships with at least one such plugin. Fall back to any plugin with declared EPs.
        val candidate = plugins
            .filter { it.declaredExtensionPointsCount > 0 }
            .firstOrNull { it.id.startsWith("com.intellij") }
            ?: plugins.firstOrNull { it.declaredExtensionPointsCount > 0 }
        assumeTrue(
            "No plugin with declaredExtensionPointsCount > 0 in this IDE build — nothing to inspect",
            candidate != null,
        )
        val pluginId = candidate!!.id
        val extensions = inventory.extensionsByPlugin(pluginId)

        // The platform almost always registers some extension against its own EPs, so a
        // strictly-non-empty assertion is realistic. If for some edition it isn't, the
        // assumption above will at least guarantee we tried.
        assertFalse(
            "extensionsByPlugin($pluginId) must find at least one extension — declared EPs imply contributions",
            extensions.isEmpty(),
        )
        for (e in extensions) {
            assertEquals(
                "Every extension returned must have providedByPluginId == $pluginId, got ${e.providedByPluginId}",
                pluginId,
                e.providedByPluginId,
            )
        }
    }

    /**
     * Unknown plugin id yields an empty list, never throws. Exercises the same `for (ep in
     * s.extensionPoints)` loop but with a filter that rejects every adapter.
     */
    fun testExtensionsByPluginReturnsEmptyForUnknownPlugin() {
        val inventory = PluginInventory.getInstance()
        val result = inventory.extensionsByPlugin("com.example.does.not.exist")
        assertTrue(
            "Unknown plugin id must produce an empty list, got ${result.size} entries",
            result.isEmpty(),
        )
    }

    /**
     * After a [PluginInventory.refresh] the underlying cache map is rebuilt with empty lists
     * for every EP. A subsequent call to [PluginInventory.extensionsForEpLive] must repopulate
     * (the [!cached.isNullOrEmpty()] short-circuit must NOT kick in on the fresh snapshot).
     */
    fun testExtensionsForEpLiveAfterRefreshReturnsFreshList() {
        val inventory = PluginInventory.getInstance()
        val eps = inventory.extensionPoints()
        val populated = eps.firstOrNull { it.extensionsCount > 0 }
        assumeTrue("Need a populated EP to test live caching", populated != null)
        val epName = populated!!.name

        val first = inventory.extensionsForEpLive(epName)
        assertFalse("First call should populate the cache with a non-empty list for $epName", first.isEmpty())

        inventory.refresh()
        val second = inventory.extensionsForEpLive(epName)
        assertFalse(
            "After refresh(), extensionsForEpLive($epName) must repopulate to a non-empty list",
            second.isEmpty(),
        )
    }

    /**
     * Smoke test for the snapshot collection over open projects: the [PluginInventory.collect]
     * method iterates [com.intellij.openapi.extensions.ExtensionsArea] for application and
     * each open project. We can't easily force a project-area EP to appear, but we can ensure
     * the snapshot's plugin+EP collection works end-to-end and exposes non-empty data.
     */
    fun testSnapshotCollectsAllOpenProjects() {
        val inventory = PluginInventory.getInstance()
        val s = inventory.snapshot()
        assertFalse("Snapshot must contain at least one plugin", s.plugins.isEmpty())
        assertFalse("Snapshot must contain at least one extension point", s.extensionPoints.isEmpty())
        // takenAtMs is set inside collect(); a reasonable lower bound is "non-zero and not in
        // the future". Just sanity-check it's been populated.
        assertTrue("Snapshot.takenAtMs must be populated, got ${s.takenAtMs}", s.takenAtMs > 0L)
    }

    /**
     * Cover the trivial read-through accessors [PluginInventory.extensionsByEp] and
     * [PluginInventory.extensionsForEp]. extensionsByEp is line 37; extensionsForEp is line 38
     * (`extensionsByEp()[name] ?: emptyList()`), both of which were uncovered.
     */
    fun testReadThroughAccessorsAreConsistentWithSnapshot() {
        val inventory = PluginInventory.getInstance()
        val s = inventory.snapshot()
        val byEp = inventory.extensionsByEp()
        assertSame(
            "extensionsByEp() must return the same map instance as snapshot().extensionsByEp",
            s.extensionsByEp,
            byEp,
        )
        // extensionsForEp(name) — known EP path
        val knownEp = s.extensionPoints.firstOrNull()
        assumeTrue("Need at least one EP to test extensionsForEp", knownEp != null)
        val list = inventory.extensionsForEp(knownEp!!.name)
        assertNotNull("extensionsForEp(${knownEp.name}) must not return null", list)
        // extensionsForEp(unknown) — null-fallback branch
        val unknown = inventory.extensionsForEp("com.example.does.not.exist")
        assertTrue("Unknown EP name must fall back to emptyList(), got ${unknown.size} entries", unknown.isEmpty())
    }
}
