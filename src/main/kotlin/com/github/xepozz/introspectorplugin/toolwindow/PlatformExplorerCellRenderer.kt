package com.github.xepozz.introspectorplugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class PlatformExplorerCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = (value as? DefaultMutableTreeNode)?.userObject as? PlatformExplorerNode ?: return
        when (node) {
            is PlatformExplorerNode.Root -> {
                icon = AllIcons.Nodes.Folder
                append(node.displayName)
            }
            is PlatformExplorerNode.PluginNode -> {
                icon = if (node.plugin.isBundled) AllIcons.Nodes.Plugin else AllIcons.Nodes.Plugin
                append(node.plugin.name)
                append("  ")
                append("[${node.plugin.id}]", SimpleTextAttributes.GRAY_ATTRIBUTES)
                if (!node.plugin.isEnabled) {
                    append("  ")
                    append("(disabled)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
                if (node.plugin.isBundled) {
                    append("  ")
                    append("[bundled]", SimpleTextAttributes.GRAY_ATTRIBUTES)
                }
            }
            is PlatformExplorerNode.GroupNode -> {
                icon = AllIcons.Nodes.Folder
                append(node.displayName)
                append("  ")
                append("(${node.count})", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            is PlatformExplorerNode.ExtensionPointNode -> {
                icon = AllIcons.Nodes.Interface
                append(node.ep.name)
                append("  ")
                append("[${node.ep.kind}]", SimpleTextAttributes.GRAY_ATTRIBUTES)
                append("  ")
                append("(${node.ep.extensionsCount})", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            is PlatformExplorerNode.ExtensionNode -> {
                icon = AllIcons.Nodes.Class
                append(node.extension.implementationClass ?: "(no impl class)")
                append("  ")
                append("← ${node.extension.providedByPluginId}", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            is PlatformExplorerNode.DependencyNode -> {
                icon = AllIcons.Nodes.PpLib
                append(node.dep.pluginId)
                append("  ")
                append(
                    if (node.dep.optional) "(optional)" else "(required)",
                    SimpleTextAttributes.GRAY_ATTRIBUTES,
                )
            }
            is PlatformExplorerNode.LoadingNode -> {
                icon = AllIcons.Process.Step_passive
                append(node.displayName, SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
        }
    }
}
