package com.github.xepozz.ide.introspector.toolwindow

import com.github.xepozz.ide.introspector.core.PluginInventory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel

/**
 * The Platform Explorer tool window content.
 *
 * Layout:
 *   ┌ toolbar ─────────────────────────┐
 *   │ filter input                     │
 *   ├──────────────────────────────────┤
 *   │ tree (top) | details (bottom)    │
 *   └──────────────────────────────────┘
 */
class PlatformExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val inventory: PluginInventory = PluginInventory.getInstance()
    private val treeModel = PlatformExplorerTreeModel(inventory, ViewMode.BY_PLUGIN, "")
    private val tree = Tree(treeModel).apply {
        cellRenderer = PlatformExplorerCellRenderer()
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }
    private val details = DetailsPanel(
        project,
        navigator = object : com.github.xepozz.ide.introspector.toolwindow.details.DetailViews.Navigator {
            override fun selectPluginById(pluginId: String) = selectNode { n ->
                n is PlatformExplorerNode.PluginNode && n.plugin.id == pluginId
            }
            override fun selectExtensionPointByName(name: String) = selectNode { n ->
                n is PlatformExplorerNode.ExtensionPointNode && n.ep.name == name
            }
        },
    )
    private val filterField = JBTextField()

    init {
        TreeSpeedSearch.installOn(tree)
        installFilter()
        installSelectionListener()
        installContextMenu()

        toolbar = buildToolbar()
        setContent(buildBody())
        rebuild()
    }

    private fun buildBody(): JComponent {
        val splitter = JBSplitter(true, 0.65f).apply {
            firstComponent = com.intellij.ui.components.JBScrollPane(tree)
            secondComponent = details
        }
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JBTextFieldWithIcon(filterField, "Filter…"), BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(ChangeViewModeAction(ViewMode.BY_PLUGIN))
            add(ChangeViewModeAction(ViewMode.BY_EXTENSION_POINT))
            add(ChangeViewModeAction(ViewMode.BY_DEPENDENCIES))
            addSeparator()
            add(ShowBundledAction())
            add(RefreshAction())
        }
        val tb = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT, group, true
        )
        tb.targetComponent = this
        return tb.component
    }

    private fun installFilter() {
        var debounce: javax.swing.Timer? = null
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { schedule() }
            override fun removeUpdate(e: DocumentEvent) { schedule() }
            override fun changedUpdate(e: DocumentEvent) { schedule() }
            private fun schedule() {
                debounce?.stop()
                debounce = javax.swing.Timer(200) {
                    treeModel.filter = filterField.text.trim()
                    rebuild()
                }.apply { isRepeats = false; start() }
            }
        })
    }

    private fun installSelectionListener() {
        tree.addTreeSelectionListener { e ->
            val node = (e.path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                as? PlatformExplorerNode ?: return@addTreeSelectionListener
            details.render(node)
        }
    }

    private fun installContextMenu() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { maybePopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybePopup(e) }
            private fun maybePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                    as? PlatformExplorerNode ?: return
                val menu = javax.swing.JPopupMenu()
                addCopyItem(menu, node)
                e.component.let { menu.show(it, e.x, e.y) }
            }
        })
    }

    private fun addCopyItem(menu: javax.swing.JPopupMenu, node: PlatformExplorerNode) {
        val label = when (node) {
            is PlatformExplorerNode.PluginNode -> "Copy Plugin ID"
            is PlatformExplorerNode.ExtensionPointNode -> "Copy EP Name"
            is PlatformExplorerNode.ExtensionNode -> "Copy Class Name"
            is PlatformExplorerNode.DependencyNode -> "Copy Plugin ID"
            else -> "Copy"
        }
        val text = when (node) {
            is PlatformExplorerNode.PluginNode -> node.plugin.id
            is PlatformExplorerNode.ExtensionPointNode -> node.ep.name
            is PlatformExplorerNode.ExtensionNode -> node.extension.implementationClass.orEmpty()
            is PlatformExplorerNode.DependencyNode -> node.dep.pluginId
            else -> node.displayName
        }
        menu.add(javax.swing.JMenuItem(label).apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })
    }

    fun rebuild() {
        ApplicationManager.getApplication().invokeLater {
            treeModel.rebuild()
        }
    }

    /**
     * Walks the tree under the current root and selects the first node matching [predicate].
     * Used by detail-panel deep-links so a click on "provided by: someplugin" jumps to that
     * plugin's node in the tree (and updates the detail panel to that node's data).
     */
    private fun selectNode(predicate: (PlatformExplorerNode) -> Boolean) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val match = findMatching(root, predicate) ?: return
        val path = javax.swing.tree.TreePath(match.path)
        tree.expandPath(path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun findMatching(
        node: DefaultMutableTreeNode,
        predicate: (PlatformExplorerNode) -> Boolean,
    ): DefaultMutableTreeNode? {
        val user = node.userObject as? PlatformExplorerNode
        if (user != null && predicate(user)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            findMatching(child, predicate)?.let { return it }
        }
        return null
    }

    private inner class ChangeViewModeAction(private val mode: ViewMode) :
        AnAction(mode.displayName, "Switch tree to ${mode.displayName} layout", iconForMode(mode)) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            treeModel.viewMode = mode
            rebuild()
        }
    }

    private fun iconForMode(mode: ViewMode) = when (mode) {
        ViewMode.BY_PLUGIN -> AllIcons.Nodes.Plugin
        ViewMode.BY_EXTENSION_POINT -> AllIcons.Nodes.Interface
        ViewMode.BY_DEPENDENCIES -> AllIcons.Nodes.PpLib
    }

    private inner class RefreshAction : AnAction("Refresh", "Reload plugin and extension data", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            // refresh() walks every plugin descriptor — push it off the EDT, then rebuild on EDT.
            ApplicationManager.getApplication().executeOnPooledThread {
                inventory.refresh()
                ApplicationManager.getApplication().invokeLater({ rebuild() }, ModalityState.any())
            }
        }
    }

    /** Toolbar toggle for hiding bundled (IDE-shipped) plugins so only third-party ones remain. */
    private inner class ShowBundledAction : ToggleAction(
        "Show Bundled Plugins",
        "Toggle visibility of plugins bundled with the IDE",
        AllIcons.General.Filter,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = treeModel.showBundled
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            treeModel.showBundled = state
            rebuild()
        }
    }
}

/** Tiny wrapper that puts the search icon inside a text field. */
private class JBTextFieldWithIcon(val field: JBTextField, hint: String) : JPanel(BorderLayout()) {
    init {
        field.emptyText.text = hint
        add(field, BorderLayout.CENTER)
    }
}
