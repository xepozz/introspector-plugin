package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.ToolWindowInfo
import com.github.xepozz.ide.introspector.model.ToolWindowsResponse
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Placeholder enumerator for the in-progress `ui.list_tool_windows` MCP tool. Returns the
 * id / display name / anchor of every registered tool window in the focused project.
 *
 * The full design (icon resolution, plugin-id attribution via ExtensionMetadata cache,
 * content-tab counts) lives in `docs/plans/ui-semantic-listing.md`. This minimal version
 * is sufficient to unblock the rest of the plugin while that feature is finished.
 */
object ToolWindowInspector {

    fun listToolWindows(includeInvisible: Boolean, nameContains: String?): ToolWindowsResponse {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return ToolWindowsResponse(
                toolWindows = emptyList(),
                project = null,
                warnings = listOf("No open project — tool windows are project-scoped."),
            )

        val mgr = ToolWindowManager.getInstance(project)
        val ids = runCatching { mgr.toolWindowIds.toList() }.getOrElse { emptyList() }
        val items = ids.mapNotNull { id ->
            val tw = runCatching { mgr.getToolWindow(id) }.getOrNull() ?: return@mapNotNull null
            val visible = runCatching { tw.isVisible }.getOrDefault(false)
            if (!includeInvisible && !visible) return@mapNotNull null
            val display = runCatching { tw.stripeTitle }.getOrNull() ?: id
            if (nameContains != null &&
                !id.contains(nameContains, ignoreCase = true) &&
                !display.contains(nameContains, ignoreCase = true)
            ) return@mapNotNull null

            ToolWindowInfo(
                id = id,
                displayName = display,
                anchor = runCatching { tw.anchor.toString() }.getOrNull(),
                visible = visible,
                active = runCatching { tw.isActive }.getOrDefault(false),
                splitMode = runCatching { tw.isSplitMode }.getOrDefault(false),
                type = runCatching { tw.type.toString() }.getOrNull(),
                iconPath = runCatching { tw.icon?.toString() }.getOrNull(),
                contentTabCount = runCatching { tw.contentManager.contentCount }.getOrDefault(0),
                providedByPluginId = null,
            )
        }
        return ToolWindowsResponse(
            toolWindows = items,
            project = project.name,
            warnings = emptyList(),
        )
    }
}
