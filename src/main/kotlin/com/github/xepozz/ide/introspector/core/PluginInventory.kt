package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.core.internal.TtlCache
import com.github.xepozz.ide.introspector.model.ExtensionInfo
import com.github.xepozz.ide.introspector.model.ExtensionPointInfo
import com.github.xepozz.ide.introspector.model.PluginDependencyInfo
import com.github.xepozz.ide.introspector.model.PluginInfo
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Application-level cache of plugin + EP + extension data. Both the MCP arch.* tools
 * and the Platform Explorer tool window read through this single source of truth.
 *
 * Cache TTL is ~30s; callers can force a [refresh] (e.g. from the tool window's
 * "Refresh" action).
 */
@Service(Service.Level.APP)
class PluginInventory {

    data class Snapshot(
        val takenAtMs: Long,
        val plugins: List<PluginInfo>,
        val extensionPoints: List<ExtensionPointInfo>,
        val extensionsByEp: Map<String, List<ExtensionInfo>>,
    )

    private val cache = TtlCache<Snapshot>(ttlMs = CACHE_TTL_MS) { collect() }

    fun snapshot(forceRefresh: Boolean = false): Snapshot = cache.get(forceRefresh)

    fun refresh() { cache.invalidate(); cache.get() }

    fun plugins(): List<PluginInfo> = snapshot().plugins
    fun extensionPoints(): List<ExtensionPointInfo> = snapshot().extensionPoints
    fun extensionsByEp(): Map<String, List<ExtensionInfo>> = snapshot().extensionsByEp
    fun extensionsForEp(name: String): List<ExtensionInfo> = extensionsByEp()[name] ?: emptyList()

    private fun collect(): Snapshot {
        val now = System.currentTimeMillis()
        val eps = ExtensionPointInspector.listExtensionPoints("both")

        val extensionsByEp = mutableMapOf<String, List<ExtensionInfo>>()
        for (ep in eps) {
            // Skip extensions enumeration here; we collect on demand to keep snapshot quick.
            // But seed empty list so consumers know the EP exists.
            extensionsByEp[ep.name] = emptyList()
        }

        // Count EPs and extensions per plugin for the PluginInfo summary fields.
        val declaredCountByPluginId = eps.groupingBy { it.declaredByPluginId }.eachCount()

        @Suppress("UnstableApiUsage")
        val plugins = PluginManagerCore.plugins.map { descriptor ->
            val depList = descriptor.dependencies
                .map { dep ->
                    PluginDependencyInfo(
                        pluginId = dep.pluginId.idString,
                        optional = dep.isOptional,
                    )
                }
            PluginInfo(
                id = descriptor.pluginId.idString,
                name = descriptor.name ?: descriptor.pluginId.idString,
                version = descriptor.version,
                vendor = descriptor.vendor,
                isBundled = descriptor.isBundled,
                isEnabled = readIsEnabled(descriptor),
                sinceBuild = descriptor.sinceBuild,
                untilBuild = descriptor.untilBuild,
                dependencies = depList,
                declaredExtensionPointsCount = declaredCountByPluginId[descriptor.pluginId.idString] ?: 0,
                registeredExtensionsCount = 0,             // computed lazily when needed
            )
        }.sortedBy { it.name.lowercase() }

        return Snapshot(now, plugins, eps, extensionsByEp)
    }

    /** Lazily computes the extensions for a single EP and updates the cache map. */
    fun extensionsForEpLive(epName: String, limit: Int = Int.MAX_VALUE): List<ExtensionInfo> {
        val current = snapshot()
        val cached = current.extensionsByEp[epName]
        if (!cached.isNullOrEmpty()) return cached.take(limit)
        val fresh = ExtensionPointInspector.listExtensionsForEp(epName, limit)
        // Mutate the snapshot's map in-place; lifetimes are fine because we never expose it externally.
        @Suppress("UNCHECKED_CAST")
        (current.extensionsByEp as MutableMap<String, List<ExtensionInfo>>)[epName] = fresh
        return fresh.take(limit)
    }

    /** Extensions registered by a specific plugin across all EPs. */
    fun extensionsByPlugin(pluginId: String): List<ExtensionInfo> {
        val s = snapshot()
        val out = mutableListOf<ExtensionInfo>()
        for (ep in s.extensionPoints) {
            val list = extensionsForEpLive(ep.name)
            list.filterTo(out) { it.providedByPluginId == pluginId }
        }
        return out
    }

    companion object {
        const val CACHE_TTL_MS = 30_000L
        fun getInstance(): PluginInventory = service()

        /** [IdeaPluginDescriptor.isEnabled] is deprecated; check via [PluginManagerCore.isDisabled]. */
        private fun readIsEnabled(d: com.intellij.ide.plugins.IdeaPluginDescriptor): Boolean =
            !PluginManagerCore.isDisabled(d.pluginId)
    }
}
