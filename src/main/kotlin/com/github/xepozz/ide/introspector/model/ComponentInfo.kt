package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

@Serializable
data class ComponentInfo(
    val id: String,
    @kotlinx.serialization.SerialName("class")
    val className: String,
    val name: String? = null,
    val accessibleName: String? = null,
    val accessibleRole: String? = null,
    val bounds: Bounds,
    val visible: Boolean,
    val enabled: Boolean,
    val text: String? = null,
    val toolTipText: String? = null,
    val properties: List<ComponentProperty> = emptyList(),
    val children: List<String> = emptyList(),
)

@Serializable
data class UiTreeResponse(
    val nodes: List<ComponentInfo>,
    val rootIds: List<String>,
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class FindComponentsResponse(
    val matches: List<ComponentInfo>,
    val total: Int,
    val warnings: List<String> = emptyList(),
)
