package com.github.xepozz.ide.introspector.toolwindow

import com.github.xepozz.ide.introspector.core.PluginInventory
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Builds the explorer's tree on demand. Computation is synchronous over [PluginInventory]'s
 * cached snapshot; child population for heavy nodes (extension lists) is lazy.
 */
class PlatformExplorerTreeModel(
    private val inventory: PluginInventory,
    var viewMode: ViewMode,
    var filter: String = "",
    var showBundled: Boolean = true,
) : DefaultTreeModel(DefaultMutableTreeNode(PlatformExplorerNode.Root("Platform Explorer"))) {

    fun rebuild() {
        val root = DefaultMutableTreeNode(PlatformExplorerNode.Root("Platform Explorer"))
        when (viewMode) {
            ViewMode.BY_PLUGIN -> populateByPlugin(root)
            ViewMode.BY_EXTENSION_POINT -> populateByEp(root)
            ViewMode.BY_DEPENDENCIES -> populateByDependencies(root)
        }
        setRoot(root)
    }

    private fun populateByPlugin(root: DefaultMutableTreeNode) {
        val plugins = inventory.plugins()
            .filter { showBundled || !it.isBundled }
            .filter { matchesFilter(it.id) || matchesFilter(it.name) }
        for (p in plugins) {
            val pluginNode = DefaultMutableTreeNode(PlatformExplorerNode.PluginNode(p))
            val eps = inventory.extensionPoints().filter { it.declaredByPluginId == p.id }
            if (eps.isNotEmpty()) {
                val group = DefaultMutableTreeNode(
                    PlatformExplorerNode.GroupNode("Declared EPs", eps.size)
                )
                for (ep in eps) {
                    group.add(DefaultMutableTreeNode(PlatformExplorerNode.ExtensionPointNode(ep)))
                }
                pluginNode.add(group)
            }
            val extensions = inventory.extensionsByPlugin(p.id)
            if (extensions.isNotEmpty()) {
                val group = DefaultMutableTreeNode(
                    PlatformExplorerNode.GroupNode("Registered extensions", extensions.size)
                )
                extensions.take(MAX_CHILDREN).forEach { e ->
                    group.add(DefaultMutableTreeNode(PlatformExplorerNode.ExtensionNode(e)))
                }
                if (extensions.size > MAX_CHILDREN) {
                    group.add(DefaultMutableTreeNode(
                        PlatformExplorerNode.LoadingNode("… and ${extensions.size - MAX_CHILDREN} more")
                    ))
                }
                pluginNode.add(group)
            }
            val services = inventory.servicesByPlugin(p.id)
            if (services.isNotEmpty()) {
                val group = DefaultMutableTreeNode(
                    PlatformExplorerNode.GroupNode("Services", services.size)
                )
                services.take(MAX_CHILDREN).forEach { s ->
                    group.add(DefaultMutableTreeNode(PlatformExplorerNode.ServiceNode(s)))
                }
                if (services.size > MAX_CHILDREN) {
                    group.add(DefaultMutableTreeNode(
                        PlatformExplorerNode.LoadingNode("… and ${services.size - MAX_CHILDREN} more")
                    ))
                }
                pluginNode.add(group)
            }
            val listeners = inventory.listenersByPlugin(p.id)
            if (listeners.isNotEmpty()) {
                val group = DefaultMutableTreeNode(
                    PlatformExplorerNode.GroupNode("Listeners", listeners.size)
                )
                listeners.take(MAX_CHILDREN).forEach { l ->
                    group.add(DefaultMutableTreeNode(PlatformExplorerNode.ListenerNode(l)))
                }
                if (listeners.size > MAX_CHILDREN) {
                    group.add(DefaultMutableTreeNode(
                        PlatformExplorerNode.LoadingNode("… and ${listeners.size - MAX_CHILDREN} more")
                    ))
                }
                pluginNode.add(group)
            }
            root.add(pluginNode)
        }
    }

    private fun populateByEp(root: DefaultMutableTreeNode) {
        val bundledIds: Set<String> = if (showBundled) emptySet()
            else inventory.plugins().filter { it.isBundled }.map { it.id }.toSet()
        val eps = inventory.extensionPoints()
            .filter { showBundled || it.declaredByPluginId !in bundledIds }
            .filter { matchesFilter(it.name) }
        for (ep in eps) {
            val epNode = DefaultMutableTreeNode(PlatformExplorerNode.ExtensionPointNode(ep))
            val list = inventory.extensionsForEpLive(ep.name)
            list.take(MAX_CHILDREN).forEach { e ->
                epNode.add(DefaultMutableTreeNode(PlatformExplorerNode.ExtensionNode(e)))
            }
            if (list.size > MAX_CHILDREN) {
                epNode.add(DefaultMutableTreeNode(
                    PlatformExplorerNode.LoadingNode("… and ${list.size - MAX_CHILDREN} more")
                ))
            }
            root.add(epNode)
        }
    }

    private fun populateByDependencies(root: DefaultMutableTreeNode) {
        val plugins = inventory.plugins()
            .filter { showBundled || !it.isBundled }
            .filter { matchesFilter(it.id) || matchesFilter(it.name) }
        for (p in plugins) {
            if (p.dependencies.isEmpty()) continue
            val pluginNode = DefaultMutableTreeNode(PlatformExplorerNode.PluginNode(p))
            for (dep in p.dependencies) {
                pluginNode.add(DefaultMutableTreeNode(PlatformExplorerNode.DependencyNode(dep)))
            }
            root.add(pluginNode)
        }
    }

    private fun matchesFilter(value: String): Boolean {
        if (filter.isEmpty()) return true
        return value.contains(filter, ignoreCase = true)
    }

    companion object {
        const val MAX_CHILDREN = 200
    }
}
