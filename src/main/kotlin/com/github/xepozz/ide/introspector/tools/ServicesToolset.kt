package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.PluginInventory
import com.github.xepozz.ide.introspector.model.ListServicesResponse
import com.github.xepozz.ide.introspector.model.ServiceInfo
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool

class ServicesToolset : McpToolset {

    @McpTool(name = "services.list")
    @McpDescription(
        """
        |Lists IntelliJ services declared in plugin.xml via `<applicationService>` /
        |`<projectService>` / `<moduleService>` across every installed plugin. Optionally also
        |enumerates `@Service`-annotated light services already instantiated in the running
        |IDE session (best-effort, non-deterministic — depends on what's been touched).
        |
        |Use this when: you want a typed view of plugin services (interface vs implementation,
        |preload mode, client/os restrictions, area) — much richer than going through
        |arch.list_extensions_for_ep("com.intellij.applicationService"). Common questions:
        |"what app-level services does plugin X register?", "what services have preload=TRUE?",
        |"is there a service for interface Y?".
        |
        |Do NOT use this when: you need ALL extensions of any kind (use arch.list_extensions_for_ep
        |with a specific EP name), or you want to inspect *components* (deprecated platform
        |construct, not exposed by this tool).
        |
        |Returns: { services: ServiceInfo[], total: int } where each ServiceInfo has
        |interfaceClass (optional — null when impl is the service interface), implementationClass
        |(canonical FQCN), testServiceImplementation/headlessImplementation (when overridden),
        |area ('application'|'project'|'module'), preload ('FALSE'|'TRUE'|'AWAIT'|'NOT_HEADLESS'|
        |'NOT_LIGHT_EDIT'), client (ClientKind name when set), os, overrides,
        |configurationSchemaKey, providedByPluginId/Name, source ('xml'|'light_instantiated').
        |
        |Examples:
        |  area="application", providedByPlugin="org.jetbrains.kotlin"   — Kotlin's app services
        |  implementationContains="ProjectManager"                       — any service implementing/extending ProjectManager
        |  includeLightInstantiated=true                                 — also list @Service classes already loaded
        """
    )
    suspend fun services_list(
        @McpDescription("'application' (most common), 'project', 'module', or 'all'. Default 'all'.")
        area: String = "all",
        @McpDescription("Restrict to services contributed by this plugin id (e.g. 'com.intellij', 'org.jetbrains.kotlin').")
        providedByPlugin: String? = null,
        @McpDescription("Case-insensitive substring filter on the declared interface FQCN.")
        interfaceContains: String? = null,
        @McpDescription("Case-insensitive substring filter on the implementation FQCN.")
        implementationContains: String? = null,
        @McpDescription("Also include @Service-annotated light services already instantiated in this IDE session. Default false — result is non-deterministic.")
        includeLightInstantiated: Boolean = false,
        @McpDescription("Cap on returned services. Default 500.")
        limit: Int = 500,
    ): ListServicesResponse {
        val inv = PluginInventory.getInstance()
        val all = mutableListOf<ServiceInfo>()
        all.addAll(inv.services())
        if (includeLightInstantiated) {
            all.addAll(inv.lightInstantiatedServices())
        }
        val filtered = all
            .filter { area == "all" || it.area == area }
            .filter { providedByPlugin == null || it.providedByPluginId == providedByPlugin }
            .filter { interfaceContains == null || (it.interfaceClass?.contains(interfaceContains, ignoreCase = true) ?: false) }
            .filter { implementationContains == null || it.implementationClass.contains(implementationContains, ignoreCase = true) }
        return ListServicesResponse(filtered.take(limit), filtered.size)
    }

    @McpTool(name = "services.find")
    @McpDescription(
        """
        |Reverse-lookup for services: given an interface or implementation FQCN, returns every
        |ServiceInfo where it matches. Use targetKind="auto" (default) to match either field.
        |
        |Use this when: you have a specific class in mind and want to know "is this registered
        |as a service?", "who provides the implementation of X?", "is interface Y backed by
        |multiple impls (via overrides=true)?".
        |
        |Do NOT use this when: you only have a substring (use services.list with
        |interfaceContains/implementationContains).
        |
        |Returns: { services: ServiceInfo[], total: int }.
        |
        |Examples:
        |  target="com.intellij.openapi.project.ProjectManager"          — every service registered against this interface
        |  target="com.intellij.openapi.project.impl.ProjectManagerImpl" — service(s) using this implementation
        """
    )
    suspend fun services_find(
        @McpDescription("Fully-qualified class name to search for. Matches interfaceClass and/or implementationClass exactly.")
        target: String,
        @McpDescription("'interface' (match interfaceClass), 'implementation' (match implementationClass), or 'auto' (default — match either).")
        targetKind: String = "auto",
    ): ListServicesResponse {
        val inv = PluginInventory.getInstance()
        val matches = inv.services().filter {
            when (targetKind) {
                "interface" -> it.interfaceClass == target
                "implementation" -> it.implementationClass == target
                else -> it.interfaceClass == target || it.implementationClass == target
            }
        }
        return ListServicesResponse(matches, matches.size)
    }
}
