package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

// Note: `ui.list_tool_windows` and `ui.list_dialogs` take only inline primitive arguments
// declared directly on their @McpTool methods (includeInvisible, nameContains, includeInvisible),
// so they do not need dedicated @Serializable args classes here.

@Serializable
data class GetUiTreeArgs(
    val maxDepth: Int = 20,
    val rootSelector: String? = null,
    val includeInvisible: Boolean = false,
    val includeProperties: Boolean = true,
    val truncatePropertyValueAt: Int = 200,
)

@Serializable
data class FindByNameArgs(
    val query: String,
    val matchMode: String = "contains",
    val caseSensitive: Boolean = false,
    val searchIn: List<String> = listOf("name", "text", "accessibleName", "toolTipText"),
    val limit: Int = 50,
)

@Serializable
data class FindByCoordinatesArgs(
    val x: Int,
    val y: Int,
    val coordinateSpace: String = "screen",
    val returnAncestors: Boolean = true,
)

@Serializable
data class FindByXPathArgs(
    val xpath: String,
    val limit: Int = 50,
)

@Serializable
data class GetPropertiesArgs(
    val componentId: String,
    val includeClientProperties: Boolean = true,
    val includeAccessibleContext: Boolean = true,
)
