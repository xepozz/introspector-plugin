package com.github.xepozz.ide.introspector.toolwindow

import com.github.xepozz.ide.introspector.core.PluginInventory
import com.github.xepozz.ide.introspector.toolwindow.details.DetailViews
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Right-side details panel. Hosts a [JBScrollPane] whose content swaps based on the currently
 * selected node. Concrete renderers live in [DetailViews]; this class is just the container +
 * scroll behaviour.
 *
 * Navigation: views accept a [DetailViews.Navigator] callback so clicking on plugin/EP links
 * deep-selects the corresponding tree node. The panel forwards those calls to its [navigator]
 * (wired in [PlatformExplorerPanel]).
 */
class DetailsPanel(
    private val project: Project,
    private val navigator: DetailViews.Navigator? = null,
) : JPanel(BorderLayout()) {

    private val scroll = JBScrollPane()
    private val views: DetailViews

    init {
        background = UIUtil.getPanelBackground()
        scroll.border = JBUI.Borders.empty()
        scroll.viewport.background = UIUtil.getPanelBackground()
        add(scroll, BorderLayout.CENTER)

        val inventory = PluginInventory.getInstance()
        views = DetailViews(
            project = project,
            navigator = navigator,
            resolveExtensionsForEp = { name -> inventory.extensionsForEpLive(name, limit = 50) },
        )

        showPlaceholder("Select a node to see its details.")
    }

    fun render(node: PlatformExplorerNode) {
        scroll.setViewportView(views.render(node))
        scroll.viewport.viewPosition = java.awt.Point(0, 0)
    }

    private fun showPlaceholder(text: String) {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
            isOpaque = false
        }
        panel.add(com.intellij.ui.components.JBLabel(text).apply {
            foreground = UIUtil.getLabelInfoForeground()
        }, BorderLayout.NORTH)
        scroll.setViewportView(panel)
    }
}
