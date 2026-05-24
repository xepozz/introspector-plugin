package com.github.xepozz.ide.introspector.toolwindow

import com.github.xepozz.ide.introspector.model.ExtensionInfo
import com.github.xepozz.ide.introspector.model.ExtensionPointInfo
import com.github.xepozz.ide.introspector.model.ListenerInfo
import com.github.xepozz.ide.introspector.model.PluginDependencyInfo
import com.github.xepozz.ide.introspector.model.PluginInfo
import com.github.xepozz.ide.introspector.model.ServiceInfo

/**
 * Sealed hierarchy of tree node user-objects. Renderers and selection handlers switch on these.
 */
sealed class PlatformExplorerNode {
    abstract val displayName: String

    data class Root(override val displayName: String) : PlatformExplorerNode()
    data class PluginNode(val plugin: PluginInfo) : PlatformExplorerNode() {
        override val displayName: String get() = "${plugin.name} [${plugin.id}]" +
            if (plugin.isBundled) " [bundled]" else ""
    }
    data class GroupNode(override val displayName: String, val count: Int) : PlatformExplorerNode()
    data class ExtensionPointNode(val ep: ExtensionPointInfo) : PlatformExplorerNode() {
        override val displayName: String get() = ep.name + " [${ep.kind}] (${ep.extensionsCount})"
    }
    data class ExtensionNode(val extension: ExtensionInfo) : PlatformExplorerNode() {
        override val displayName: String get() = extension.implementationClass ?: "(no impl class)"
    }
    data class DependencyNode(val dep: PluginDependencyInfo) : PlatformExplorerNode() {
        override val displayName: String get() = dep.pluginId +
            if (dep.optional) " (optional)" else " (required)"
    }
    data class ServiceNode(val service: ServiceInfo) : PlatformExplorerNode() {
        override val displayName: String get() = service.implementationClass +
            " [${service.area}]"
    }
    data class ListenerNode(val listener: ListenerInfo) : PlatformExplorerNode() {
        override val displayName: String get() =
            "${listener.listenerClass} → ${listener.topicClass.substringAfterLast('.')}"
    }
    data class LoadingNode(override val displayName: String = "Loading…") : PlatformExplorerNode()
}
