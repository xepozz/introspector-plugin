package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.Bounds
import com.github.xepozz.ide.introspector.model.DialogInfo
import com.github.xepozz.ide.introspector.model.DialogsResponse
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Dialog
import java.awt.Window

/**
 * Placeholder enumerator for the in-progress `ui.list_dialogs` MCP tool. Walks every live
 * AWT [Window], filters to [Dialog]s, and resolves the contributing [DialogWrapper] class
 * where possible.
 *
 * The fuller design (component-id registration via [ComponentRegistry], modality semantics)
 * lives in `docs/plans/ui-semantic-listing.md`.
 */
object DialogInspector {

    fun listDialogs(includeInvisible: Boolean): DialogsResponse {
        val registry = ComponentRegistry.getInstance()
        val windows: Array<Window> = runCatching { Window.getWindows() }.getOrElse { emptyArray() }
        val items = windows.mapNotNull { w ->
            val dlg = w as? Dialog ?: return@mapNotNull null
            if (!includeInvisible && !dlg.isShowing) return@mapNotNull null

            val id = registry.register(dlg)
            val b = dlg.bounds
            val content = runCatching { DialogWrapper.findInstance(dlg)?.javaClass?.name }.getOrNull()
                ?: dlg.javaClass.name

            DialogInfo(
                componentId = id,
                title = runCatching { dlg.title }.getOrNull(),
                bounds = Bounds(b.x, b.y, b.width, b.height),
                modal = runCatching { dlg.isModal }.getOrDefault(false),
                resizable = runCatching { dlg.isResizable }.getOrDefault(false),
                contentClass = content,
            )
        }
        return DialogsResponse(dialogs = items, warnings = emptyList())
    }
}
