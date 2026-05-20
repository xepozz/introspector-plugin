package com.github.xepozz.introspectorplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class ExtensionInfo(
    val extensionPointName: String,
    val implementationClass: String?,
    val providedByPluginId: String,
    val providedByPluginName: String?,
    val additionalAttributes: Map<String, String> = emptyMap(),
)

@Serializable
data class ListExtensionsResponse(
    val extensions: List<ExtensionInfo>,
    val total: Int,
)
