package com.github.xepozz.introspectorplugin.tools

import com.github.xepozz.introspectorplugin.core.PluginInventory
import com.github.xepozz.introspectorplugin.model.ExtensionInfo
import com.github.xepozz.introspectorplugin.model.ListExtensionPointsResponse
import com.github.xepozz.introspectorplugin.model.ListExtensionsResponse
import com.github.xepozz.introspectorplugin.model.ListPluginsResponse
import com.github.xepozz.introspectorplugin.model.PluginDetails
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.extensions.PluginId
import kotlinx.serialization.json.JsonObject

class ArchitectureToolset : McpToolset {

    @McpTool(name = "arch.list_extension_points")
    @McpDescription(
        """Lists every Extension Point currently registered in this IDE instance.
Filter by area ("application" | "project" | "both"), by declaring plugin id, by name substring,
or by isDynamic. Use this to discover available hooks before building pre-built tools, then
arch.list_extensions_for_ep to see who plugged into a given EP."""
    )
    suspend fun arch_list_extension_points(
        @McpDescription("application | project | both") area: String = "application",
        @McpDescription("Filter by declarer plugin id") declaredByPlugin: String? = null,
        @McpDescription("Case-insensitive substring filter on EP name") nameContains: String? = null,
        @McpDescription("Only EPs marked dynamic") onlyDynamic: Boolean = false,
        @McpDescription("Max EPs returned") limit: Int = 500,
    ): ListExtensionPointsResponse {
        val all = PluginInventory.getInstance().extensionPoints()
            .filter { area == "both" || it.area == area }
            .filter { declaredByPlugin == null || it.declaredByPluginId == declaredByPlugin }
            .filter { nameContains == null || it.name.contains(nameContains, ignoreCase = true) }
            .filter { !onlyDynamic || it.isDynamic }
        return ListExtensionPointsResponse(all.take(limit), all.size)
    }

    @McpTool(name = "arch.list_extensions_for_ep")
    @McpDescription(
        """Lists every extension registered against a given Extension Point name, with the
plugin that contributed it and the XML attributes from its declaration."""
    )
    suspend fun arch_list_extensions_for_ep(
        @McpDescription("Fully qualified EP name") extensionPointName: String,
        @McpDescription("Max extensions returned") limit: Int = 200,
    ): ListExtensionsResponse {
        val list = PluginInventory.getInstance().extensionsForEpLive(extensionPointName, limit)
        return ListExtensionsResponse(list, list.size)
    }

    @McpTool(name = "arch.list_plugins")
    @McpDescription(
        """Lists installed plugins (bundled and external by default). Filter by name/id substring,
by bundled-ness, or by enabled state."""
    )
    suspend fun arch_list_plugins(
        @McpDescription("Include bundled plugins") includeBundled: Boolean = true,
        @McpDescription("Include disabled plugins") includeDisabled: Boolean = false,
        @McpDescription("Substring filter on name or id") nameOrIdContains: String? = null,
    ): ListPluginsResponse {
        val list = PluginInventory.getInstance().plugins()
            .filter { includeBundled || !it.isBundled }
            .filter { includeDisabled || it.isEnabled }
            .filter {
                val q = nameOrIdContains ?: return@filter true
                it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
            }
        return ListPluginsResponse(list, list.size)
    }

    @McpTool(name = "arch.get_plugin_details")
    @McpDescription(
        """Returns rich detail for a single plugin id: declared extension points it owns,
every extension it registers across all EPs, and optionally the action-id inventory."""
    )
    suspend fun arch_get_plugin_details(
        @McpDescription("Plugin id, e.g. 'com.intellij.java'") pluginId: String,
        @McpDescription("Include declared EPs") includeDeclaredExtensionPoints: Boolean = true,
        @McpDescription("Include registered extensions") includeRegisteredExtensions: Boolean = true,
        @McpDescription("Include action ids (off by default — expensive)") includeActions: Boolean = false,
    ): PluginDetails {
        val inv = PluginInventory.getInstance()
        val plugin = inv.plugins().firstOrNull { it.id == pluginId }
            ?: throw McpExpectedError("Unknown plugin id: $pluginId", JsonObject(emptyMap()))
        val declaredEps = if (includeDeclaredExtensionPoints) {
            inv.extensionPoints().filter { it.declaredByPluginId == pluginId }
        } else emptyList()
        val extensions = if (includeRegisteredExtensions) inv.extensionsByPlugin(pluginId) else emptyList()
        val actions = if (includeActions) actionsFor(pluginId) else emptyList()
        return PluginDetails(plugin, declaredEps, extensions, actions)
    }

    @McpTool(name = "arch.find_extenders_of")
    @McpDescription(
        """Reverse search: given an Extension Point name or a fully-qualified interface/class,
returns every extension instance that plugs into it. targetKind = "extension_point" | "interface" | "auto"
(default). With "auto", the target is first looked up as an EP; if not found, treated as a class name."""
    )
    suspend fun arch_find_extenders_of(
        @McpDescription("EP name or fully qualified class") target: String,
        @McpDescription("extension_point | interface | auto") targetKind: String = "auto",
    ): ListExtensionsResponse {
        val inv = PluginInventory.getInstance()
        val asEp = inv.extensionPoints().any { it.name == target }
        val kind = when (targetKind) {
            "extension_point", "interface" -> targetKind
            else -> if (asEp) "extension_point" else "interface"
        }
        val extensions: List<ExtensionInfo> = if (kind == "extension_point") {
            inv.extensionsForEpLive(target)
        } else {
            val out = mutableListOf<ExtensionInfo>()
            for (ep in inv.extensionPoints()) {
                inv.extensionsForEpLive(ep.name).forEach {
                    if (it.implementationClass == target) out.add(it)
                }
            }
            out
        }
        return ListExtensionsResponse(extensions, extensions.size)
    }

    private fun actionsFor(pluginId: String): List<String> = runCatching {
        val am = ActionManagerEx.getInstanceEx()
        val pid = PluginId.getId(pluginId)
        am.getPluginActions(pid).toList()
    }.getOrElse { emptyList() }
}
