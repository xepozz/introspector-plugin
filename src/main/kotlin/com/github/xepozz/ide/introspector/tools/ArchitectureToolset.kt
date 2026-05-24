package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.PluginInventory
import com.github.xepozz.ide.introspector.model.ExtensionInfo
import com.github.xepozz.ide.introspector.model.ListExtensionPointsResponse
import com.github.xepozz.ide.introspector.model.ListExtensionsResponse
import com.github.xepozz.ide.introspector.model.ListPluginsResponse
import com.github.xepozz.ide.introspector.model.PluginDetails
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
        """
        |Enumerates Extension Points (EPs) live from this specific IDE instance — bundled
        |platform EPs plus any contributed by installed plugins. Reflects the *actual* running
        |state, not what the docs or Marketplace say, so disabled plugins / dev builds /
        |custom installs are accurate.
        |
        |Use this when: a user (or a plugin-development task) asks "what extension points are
        |available?", "is there a hook for X?", "what EPs does plugin Y own?". This is the
        |entry point for plugin-architecture exploration.
        |
        |Follow-up tools:
        |  - arch.list_extensions_for_ep   — who has plugged into a given EP
        |  - arch.find_extenders_of        — reverse search by EP name or class
        |  - arch.get_plugin_details       — full inventory for one plugin
        |
        |Do NOT use this when: you need to count installed plugins (use arch.list_plugins), or
        |when looking for classes that implement an interface unrelated to EPs (the IDE doesn't
        |index them that way — try arch.find_extenders_of with targetKind="interface").
        |
        |Returns: { extensionPoints: ExtensionPointInfo[], total: int } where each EP carries
        |name, kind ('INTERFACE'|'BEAN_CLASS'), interfaceOrBeanClass (FQCN), declaredByPluginId,
        |declaredByPluginName, isDynamic, extensionsCount, area ('application'|'project').
        |
        |Vanilla IDEA Community has ≥1000 EPs at application area — narrow with nameContains
        |("toolWindow", "configurable", etc.) before reading the response.
        |
        |Examples:
        |  nameContains="toolWindow"                              — every tool-window EP
        |  area="project", declaredByPlugin="com.intellij"        — project-area platform EPs
        |  nameContains="inspection", onlyDynamic=true            — dynamic inspection EPs only
        """
    )
    suspend fun arch_list_extension_points(
        @McpDescription("'application' (most common), 'project' (per-project EPs), or 'both'. Default 'application'.")
        area: String = "application",
        @McpDescription("Restrict to EPs whose declaring plugin id matches exactly, e.g. 'com.intellij' or 'com.jetbrains.php'.")
        declaredByPlugin: String? = null,
        @McpDescription("Case-insensitive substring filter on EP name, e.g. 'toolWindow' or 'configurable'. Strongly recommended — full list can be 1000+.")
        nameContains: String? = null,
        @McpDescription("Restrict to EPs marked dynamic=true (hot-swappable via dynamic plugins).")
        onlyDynamic: Boolean = false,
        @McpDescription("Cap on returned EPs. Default 500.")
        limit: Int = 500,
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
        """
        |Lists every extension registered against one specific Extension Point — i.e. every
        |plugin's contribution to that hook. For each extension you get the user's
        |implementation class, the contributing plugin, and all XML attributes from the
        |original declaration (factoryClass, anchor, id, …).
        |
        |Use this when: you've identified an EP of interest (via arch.list_extension_points)
        |and want to see who plugs into it — e.g. "what tool windows are registered?", "what
        |inspections does plugin X add?".
        |
        |Do NOT use this when: you want extensions across multiple EPs by some other criterion
        |(use arch.find_extenders_of or arch.get_plugin_details).
        |
        |Returns: { extensions: ExtensionInfo[], total: int } where each ExtensionInfo has
        |extensionPointName, implementationClass (bean class for BEAN_CLASS EPs, user class
        |for INTERFACE EPs), effectiveClass (user's actual class extracted from XML
        |attributes like factoryClass/instance/serviceImplementation/implementation),
        |providedByPluginId, providedByPluginName, additionalAttributes (full XML attribute
        |map: id, anchor, icon, etc.).
        |
        |Examples:
        |  extensionPointName="com.intellij.toolWindow"                  — every registered ToolWindowFactory with anchor/id/icon/plugin
        |  extensionPointName="com.intellij.applicationConfigurable"     — every settings panel contributed by any plugin
        """
    )
    suspend fun arch_list_extensions_for_ep(
        @McpDescription("Fully qualified EP name as returned by arch.list_extension_points (e.g. 'com.intellij.toolWindow', 'com.intellij.applicationConfigurable').")
        extensionPointName: String,
        @McpDescription("Cap on returned extensions. Default 200.")
        limit: Int = 200,
    ): ListExtensionsResponse {
        val list = PluginInventory.getInstance().extensionsForEpLive(extensionPointName, limit)
        return ListExtensionsResponse(list, list.size)
    }

    @McpTool(name = "arch.list_plugins")
    @McpDescription(
        """
        |Lists every plugin installed in this IDE instance, with id, name, version, vendor,
        |bundled/enabled flags, sinceBuild/untilBuild compatibility range, declared dependencies,
        |and counts of declared EPs / registered extensions. Reflects the live PluginManager
        |state — disabled or third-party plugins included by default.
        |
        |Use this when: you need to know what's installed, find a plugin by partial name to
        |get its id, or audit version / compatibility.
        |
        |Do NOT use this when: you want what the plugin *does* (extensions, EPs) — call
        |arch.get_plugin_details with the id from this list.
        |
        |Returns: { plugins: PluginInfo[], total: int }. A typical IDEA install has 50-150
        |bundled plugins — set includeBundled=false to focus on third-party.
        |
        |Examples:
        |  includeBundled=false                                   — only third-party plugins
        |  nameOrIdContains="kotlin"                              — Kotlin-related plugins
        |  includeDisabled=true, nameOrIdContains="docker"        — find a disabled Docker plugin
        """
    )
    suspend fun arch_list_plugins(
        @McpDescription("Include plugins bundled with the IDE. Default true. Set false to focus on third-party plugins.")
        includeBundled: Boolean = true,
        @McpDescription("Include plugins the user has disabled. Default false (enabled only).")
        includeDisabled: Boolean = false,
        @McpDescription("Case-insensitive substring filter on plugin name OR plugin id.")
        nameOrIdContains: String? = null,
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
        """
        |Returns the full inventory for one plugin: its metadata (version, vendor, deps),
        |every EP it declares, every extension it contributes (across all EPs), and optionally
        |all its action ids.
        |
        |Use this when: you want to understand what a single plugin actually does, what hooks
        |it exposes for others to extend, or what files (factories, services) it ships. Common
        |pattern: arch.list_plugins → arch.get_plugin_details (with the id).
        |
        |Do NOT use this when: you want extensions across multiple plugins
        |(arch.list_extensions_for_ep or arch.find_extenders_of), or just plugin counts
        |(arch.list_plugins).
        |
        |Returns: { plugin: PluginInfo, declaredExtensionPoints: ExtensionPointInfo[],
        |registeredExtensions: ExtensionInfo[], actions: string[] }.
        |
        |Caveats:
        |  - includeActions=false by default — populating action ids touches the action
        |    manager and can be slow for plugins with hundreds of actions (e.g. com.intellij).
        |  - For large plugins (com.intellij itself owns ~1000 EPs and contributes ~500
        |    extensions) the response can be huge — consider whether the narrower
        |    arch.list_extensions_for_ep is what you actually need.
        |
        |Examples:
        |  pluginId="com.github.xepozz.ide.introspector"                            — this plugin's inventory
        |  pluginId="org.jetbrains.kotlin", includeRegisteredExtensions=false         — only declared EPs
        |  pluginId="com.intellij.java", includeActions=true                          — full inventory + actions (slow)
        """
    )
    suspend fun arch_get_plugin_details(
        @McpDescription("Plugin id, e.g. 'com.intellij.java', 'org.jetbrains.kotlin', 'com.github.xepozz.ide.introspector'. Get ids from arch.list_plugins.")
        pluginId: String,
        @McpDescription("Include EPs this plugin declares (i.e. extensibility hooks it offers to others). Default true.")
        includeDeclaredExtensionPoints: Boolean = true,
        @McpDescription("Include extensions this plugin contributes to other plugins' EPs. Default true.")
        includeRegisteredExtensions: Boolean = true,
        @McpDescription("Include services declared by this plugin (application/project/module). Default true — cheap.")
        includeServices: Boolean = true,
        @McpDescription("Include message-bus listeners declared by this plugin (application/project). Default true — cheap.")
        includeListeners: Boolean = true,
        @McpDescription("Include the plugin's action ids. Default false — slow on plugins with many actions (com.intellij has ~3000).")
        includeActions: Boolean = false,
    ): PluginDetails {
        val inv = PluginInventory.getInstance()
        val plugin = inv.plugins().firstOrNull { it.id == pluginId }
            ?: throw McpExpectedError("Unknown plugin id: $pluginId", JsonObject(emptyMap()))
        val declaredEps = if (includeDeclaredExtensionPoints) {
            inv.extensionPoints().filter { it.declaredByPluginId == pluginId }
        } else emptyList()
        val extensions = if (includeRegisteredExtensions) inv.extensionsByPlugin(pluginId) else emptyList()
        val services = if (includeServices) inv.servicesByPlugin(pluginId) else emptyList()
        val listeners = if (includeListeners) inv.listenersByPlugin(pluginId) else emptyList()
        val actions = if (includeActions) actionsFor(pluginId) else emptyList()
        return PluginDetails(plugin, declaredEps, extensions, services, listeners, actions)
    }

    @McpTool(name = "arch.find_extenders_of")
    @McpDescription(
        """
        |Reverse-lookup: "who implements / plugs into X?". Given an EP name or a fully-qualified
        |class, returns every extension that registers against it. Use targetKind="auto" (default)
        |to let the tool decide — it first tries the target as an EP name; if no such EP exists,
        |it scans all EPs for extensions whose implementationClass matches.
        |
        |Use this when: a user asks "how is X done in IntelliJ?", "who provides Y?", "what plugin
        |adds the database tool window?" — i.e. starting from a known interface/EP and looking
        |for concrete implementations.
        |
        |Do NOT use this when: you already know the EP name and just want its extensions
        |(arch.list_extensions_for_ep is more direct), or you have a plugin id
        |(arch.get_plugin_details).
        |
        |Returns: { extensions: ExtensionInfo[], total: int }.
        |
        |Examples:
        |  target="com.intellij.toolWindow"                       — every ToolWindowFactory
        |  target="com.intellij.openapi.fileTypes.FileTypeFactory" — every FileTypeFactory impl
        |  target="com.intellij.codeInsight.intention.IntentionAction" — every IntentionAction impl
        """
    )
    suspend fun arch_find_extenders_of(
        @McpDescription("EP name (e.g. 'com.intellij.toolWindow') or fully-qualified class/interface name. Use the kind that matches what you have.")
        target: String,
        @McpDescription("'extension_point' (treat target as EP name), 'interface' (treat as class), or 'auto' (default — EP first, fall back to class scan).")
        targetKind: String = "auto",
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
