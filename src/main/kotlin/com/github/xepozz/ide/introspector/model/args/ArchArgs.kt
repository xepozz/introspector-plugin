package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

@Serializable
data class ListExtensionPointsArgs(
    val area: String = "application",        // "application" | "project" | "both"
    val declaredByPlugin: String? = null,
    val nameContains: String? = null,
    val onlyDynamic: Boolean = false,
    val limit: Int = 500,
)

@Serializable
data class ListExtensionsForEpArgs(
    val extensionPointName: String,
    val limit: Int = 200,
)

@Serializable
data class ListPluginsArgs(
    val includeBundled: Boolean = true,
    val includeDisabled: Boolean = false,
    val nameOrIdContains: String? = null,
)

@Serializable
data class GetPluginDetailsArgs(
    val pluginId: String,
    val includeDeclaredExtensionPoints: Boolean = true,
    val includeRegisteredExtensions: Boolean = true,
    val includeActions: Boolean = false,
)

@Serializable
data class FindExtendersOfArgs(
    val target: String,
    val targetKind: String = "auto",         // "extension_point" | "interface" | "auto"
)

/**
 * Args for `arch.check_lock_requirements`. Mirrors `psi.find_usages` position semantics —
 * `target` is mutually exclusive with `fileUrl` + (offset | line+column). The two args
 * classes (this one and [CheckThreadingRequirementsArgs]) have identical field lists by
 * design — see `docs/plans/arch-devkit-mirror.md`. They're kept distinct so the KSP doc
 * processor renders separate `@McpDescription`s for each tool.
 */
@Serializable
data class CheckLockRequirementsArgs(
    val target: String? = null,
    val fileUrl: String? = null,
    val offset: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    val scope: String = "project",
    val includeImplementations: Boolean = true,
    val maxCallSites: Int = 500,
)

/** Args for `arch.check_threading_requirements`. See [CheckLockRequirementsArgs]. */
@Serializable
data class CheckThreadingRequirementsArgs(
    val target: String? = null,
    val fileUrl: String? = null,
    val offset: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    val scope: String = "project",
    val includeImplementations: Boolean = true,
    val maxCallSites: Int = 500,
@Serializable
data class ListServicesArgs(
    val scope: String = "all",               // "application" | "project" | "module" | "all"
    val providedByPluginId: String? = null,
    val nameContains: String? = null,
    val onlyPreloaded: Boolean = false,
    val limit: Int = 500,
@Serializable
data class ListListenersArgs(
    val topicContains: String? = null,
    val providedByPluginId: String? = null,
    val scope: String = "both",              // "application" | "project" | "both"
    val limit: Int = 300,
@Serializable
data class ListActionsArgs(
    val query: String? = null,
    val providedByPluginId: String? = null,
    val includeInternal: Boolean = false,
    val limit: Int = 200,
)
