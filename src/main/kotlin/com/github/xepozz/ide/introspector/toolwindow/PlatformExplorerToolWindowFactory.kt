package com.github.xepozz.ide.introspector.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class PlatformExplorerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PlatformExplorerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
