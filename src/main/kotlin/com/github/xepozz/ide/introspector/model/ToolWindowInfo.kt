package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Lightweight semantic descriptor of one registered tool window. See [ToolWindowsResponse].
 *
 * NOTE: This is a placeholder shape kept to unblock compilation while the
 * `ui-semantic-listing` feature is still in flight. The real implementation may extend
 * this class with additional fields (anchor type, content tabs, etc.) — see
 * `docs/plans/ui-semantic-listing.md`.
 */
@Serializable
data class ToolWindowInfo(
    val id: String,
    val displayName: String? = null,
    val anchor: String? = null,
    val visible: Boolean = false,
    val active: Boolean = false,
    val splitMode: Boolean = false,
    val type: String? = null,
    val iconPath: String? = null,
    val contentTabCount: Int = 0,
    val providedByPluginId: String? = null,
)

@Serializable
data class ToolWindowsResponse(
    val toolWindows: List<ToolWindowInfo> = emptyList(),
    val project: String? = null,
    val warnings: List<String> = emptyList(),
)
