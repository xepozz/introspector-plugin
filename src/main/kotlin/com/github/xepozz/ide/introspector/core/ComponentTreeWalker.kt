package com.github.xepozz.ide.introspector.core

import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.openapi.project.ProjectManager
import java.awt.Component
import java.awt.Container
import java.awt.Window

/**
 * Provides UI root collection and traversal. All methods must be called on the EDT
 * because `Component.getComponents()` and parent/child links are not thread-safe.
 */
object ComponentTreeWalker {

    /** Returns all top-level Windows that should be considered "the IDE UI". */
    fun collectRoots(rootSelector: String?): List<Component> {
        val all = mutableListOf<Component>()

        // All project IDE frames.
        for (project in ProjectManager.getInstance().openProjects) {
            WindowManager.getInstance().getFrame(project)?.let { all.add(it) }
        }
        // Welcome screen frame when no project is open.
        WindowManager.getInstance().findVisibleFrame()?.let {
            if (it !in all) all.add(it)
        }
        // All other Windows (popups, dialogs, balloons).
        for (w in Window.getWindows()) {
            if (w.isDisplayable && w !in all) all.add(w)
        }

        return when {
            rootSelector == null -> all
            rootSelector == "frame" -> all.filter { it is java.awt.Frame }
            rootSelector == "dialog" -> all.filter { it is java.awt.Dialog }
            rootSelector.startsWith("tool_window:") -> {
                val toolWindowId = rootSelector.removePrefix("tool_window:")
                collectToolWindowRoots(toolWindowId)
            }
            else -> all
        }
    }

    private fun collectToolWindowRoots(id: String): List<Component> {
        val result = mutableListOf<Component>()
        for (project in ProjectManager.getInstance().openProjects) {
            val twm = ToolWindowManagerEx.getInstanceEx(project)
            val tw = twm.getToolWindow(id) ?: continue
            try {
                tw.contentManager.contents.forEach { content ->
                    content.component?.let { result.add(it) }
                }
            } catch (_: Throwable) {
                // Tool window may not be initialised yet — ignore.
            }
        }
        return result
    }

    /**
     * BFS over the Swing hierarchy rooted at [root], visiting up to [maxDepth] levels.
     * Returns each visited component once.
     */
    fun walk(root: Component, maxDepth: Int, includeInvisible: Boolean, visitor: (Component, Int) -> Boolean) {
        val queue: ArrayDeque<Pair<Component, Int>> = ArrayDeque()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            val (c, depth) = queue.removeFirst()
            if (!includeInvisible && !c.isVisible) continue
            val cont = visitor(c, depth)
            if (!cont) continue
            if (depth >= maxDepth) continue
            if (c is Container) {
                for (child in c.components) {
                    if (child != null) queue.addLast(child to depth + 1)
                }
            }
            // JWindow / popups attach their content via getOwnedWindows
            if (c is Window) {
                for (owned in c.ownedWindows) {
                    if (owned != null) queue.addLast(owned to depth + 1)
                }
            }
        }
    }

    /** Returns the ancestor chain from [c] up to the root window. */
    fun ancestors(c: Component): List<Component> {
        val chain = mutableListOf<Component>()
        var cur: Component? = c.parent
        while (cur != null) {
            chain.add(cur)
            cur = cur.parent
        }
        return chain
    }
}
