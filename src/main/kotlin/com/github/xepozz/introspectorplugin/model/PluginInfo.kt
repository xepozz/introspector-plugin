package com.github.xepozz.introspectorplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class PluginDependencyInfo(
    val pluginId: String,
    val optional: Boolean,
)

@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String?,
    val vendor: String?,
    val isBundled: Boolean,
    val isEnabled: Boolean,
    val sinceBuild: String? = null,
    val untilBuild: String? = null,
    val dependencies: List<PluginDependencyInfo> = emptyList(),
    val declaredExtensionPointsCount: Int = 0,
    val registeredExtensionsCount: Int = 0,
)

@Serializable
data class ListPluginsResponse(
    val plugins: List<PluginInfo>,
    val total: Int,
)

@Serializable
data class PluginDetails(
    val plugin: PluginInfo,
    val declaredExtensionPoints: List<ExtensionPointInfo> = emptyList(),
    val registeredExtensions: List<ExtensionInfo> = emptyList(),
    val actions: List<String> = emptyList(),
)
