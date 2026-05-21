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
