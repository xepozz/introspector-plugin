package com.github.xepozz.ide.introspector.toolwindow.details

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Compact horizontal "Plugin > EP > Extension" trail rendered above the page header. Each
 * segment with an [onClick] callback becomes an ActionLink that selects the corresponding tree
 * node; the trailing segment (= the currently-displayed node) is shown as plain bold text.
 */
object Breadcrumb {

    data class Segment(
        val text: String,
        val icon: Icon? = null,
        val onClick: (() -> Unit)? = null,
    )

    fun render(vararg segments: Segment): JComponent {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 0, 12)
        }
        for ((i, seg) in segments.withIndex()) {
            if (i > 0) bar.add(separator())
            bar.add(segmentComponent(seg, terminal = i == segments.lastIndex))
        }
        return bar
    }

    private fun segmentComponent(seg: Segment, terminal: Boolean): JComponent {
        val onClick = seg.onClick
        val component: JComponent = if (terminal || onClick == null) {
            JBLabel(seg.text, seg.icon, JLabel.LEADING).apply {
                if (terminal) font = font.deriveFont(java.awt.Font.BOLD)
            }
        } else {
            actionLink(seg.text, onClick).apply { icon = seg.icon }
        }
        component.alignmentY = Component.CENTER_ALIGNMENT
        return component
    }

    private fun separator(): JComponent = JBLabel(" › ").apply {
        foreground = UIUtil.getLabelInfoForeground()
        border = JBUI.Borders.empty(0, 4)
    }

    // ---------- icon presets shared by callers ----------
    val PLUGIN_ICON: Icon get() = AllIcons.Nodes.Plugin
    val EP_ICON: Icon get() = AllIcons.Nodes.Interface
    val EXT_ICON: Icon get() = AllIcons.Nodes.Class
    val DEP_ICON: Icon get() = AllIcons.Nodes.PpLib
}
