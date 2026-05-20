package com.github.xepozz.introspectorplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class ExtensionPointInfo(
    val name: String,
    val kind: String,                       // "INTERFACE" | "BEAN_CLASS"
    val interfaceOrBeanClass: String,
    val declaredByPluginId: String,
    val declaredByPluginName: String?,
    val isDynamic: Boolean,
    val extensionsCount: Int,
    val area: String,                       // "application" | "project"
)

@Serializable
data class ListExtensionPointsResponse(
    val extensionPoints: List<ExtensionPointInfo>,
    val total: Int,
)
