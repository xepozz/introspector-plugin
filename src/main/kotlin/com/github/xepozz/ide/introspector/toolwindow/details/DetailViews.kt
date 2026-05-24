package com.github.xepozz.ide.introspector.toolwindow.details

import com.github.xepozz.ide.introspector.model.ExtensionInfo
import com.github.xepozz.ide.introspector.model.ExtensionPointInfo
import com.github.xepozz.ide.introspector.model.ListenerInfo
import com.github.xepozz.ide.introspector.model.PluginDependencyInfo
import com.github.xepozz.ide.introspector.model.PluginInfo
import com.github.xepozz.ide.introspector.model.ServiceInfo
import com.github.xepozz.ide.introspector.toolwindow.PlatformExplorerNode
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Per-node renderers. Each `render*` returns a self-contained vertical panel.
 *
 * [navigator] is an optional callback the views invoke to deep-link sibling nodes — passing the
 * key (plugin id / EP name) the host panel should select in the tree. When null, those links
 * become plain labels.
 */
class DetailViews(
    private val project: Project,
    private val navigator: Navigator? = null,
    private val resolveExtensionsForEp: (String) -> List<ExtensionInfo> = { emptyList() },
) {

    interface Navigator {
        fun selectPluginById(pluginId: String)
        fun selectExtensionPointByName(name: String)
    }

    fun render(node: PlatformExplorerNode): JComponent = when (node) {
        is PlatformExplorerNode.PluginNode -> renderPlugin(node.plugin)
        is PlatformExplorerNode.ExtensionPointNode -> renderExtensionPoint(node.ep)
        is PlatformExplorerNode.ExtensionNode -> renderExtension(node.extension)
        is PlatformExplorerNode.DependencyNode -> renderDependency(node.dep)
        is PlatformExplorerNode.ServiceNode -> renderService(node.service)
        is PlatformExplorerNode.ListenerNode -> renderListener(node.listener)
        is PlatformExplorerNode.GroupNode -> emptyState("${node.displayName}: ${node.count} items.")
        is PlatformExplorerNode.Root -> emptyState("Choose a plugin or extension point on the left.")
        is PlatformExplorerNode.LoadingNode -> emptyState(node.displayName)
    }

    // ---------- Plugin ----------

    private fun renderPlugin(p: PluginInfo): JComponent {
        val chips = ChipStrip(buildList<JComponent> {
            add(Chips.enabled(p.isEnabled))
            if (p.isBundled) add(Chips.bundled())
            add(Chips.count("EPs", p.declaredExtensionPointsCount))
        })
        val form = DetailForm()
            .row("Id", copyableMonospace(p.id))
            .row("Version", p.version)
            .row("Vendor", p.vendor)
            .row("Since build", p.sinceBuild)
            .row("Until build", p.untilBuild)

        if (p.dependencies.isNotEmpty()) {
            form.section("Dependencies (${p.dependencies.size})")
            for (dep in p.dependencies) {
                form.row(if (dep.optional) "optional" else "required", dependencyLink(dep))
            }
        }

        val crumbs = Breadcrumb.render(
            Breadcrumb.Segment(p.name, icon = Breadcrumb.PLUGIN_ICON),
        )
        return wrap(crumbs, pageHeader(p.name, subtitle = p.id, chips = chips), form.build())
    }

    // ---------- Extension Point ----------

    private fun renderExtensionPoint(ep: ExtensionPointInfo): JComponent {
        val chips = ChipStrip(
            Chips.forKind(ep.kind),
            Chips.forArea(ep.area),
            Chips.dynamic(ep.isDynamic),
            Chips.count("extensions", ep.extensionsCount),
        )
        val form = DetailForm()
            .row("Name", copyableMonospace(ep.name))
            .row(
                if (ep.kind == "INTERFACE") "Interface" else "Bean class",
                FqnLink.render(project, ep.interfaceOrBeanClass),
            )
            .row("Declared by", pluginLink(ep.declaredByPluginId, ep.declaredByPluginName))

        // Related extensions preview — first 10 implementation classes, clickable to navigate.
        val extensions = runCatching { resolveExtensionsForEp(ep.name) }.getOrElse { emptyList() }
        if (extensions.isNotEmpty()) {
            form.section("Extensions (${ep.extensionsCount})")
            for (e in extensions.take(10)) {
                val impl = e.effectiveClass ?: e.implementationClass ?: "(no impl)"
                form.row(
                    e.providedByPluginName ?: e.providedByPluginId,
                    FqnLink.render(project, impl),
                )
            }
            if (extensions.size > 10) {
                form.custom(JBLabel("… and ${extensions.size - 10} more").apply {
                    foreground = UIUtil.getLabelInfoForeground()
                    border = JBUI.Borders.emptyTop(4)
                })
            }
        }

        // External "Open in Platform Explorer (web)" — keeps the existing affordance.
        form.separator().custom(actionLink("Open in Platform Explorer (web)") {
            BrowserUtil.browse(
                "https://plugins.jetbrains.com/intellij-platform-explorer/extensions?extensions=${ep.name}"
            )
        })

        val crumbs = Breadcrumb.render(
            pluginSegment(ep.declaredByPluginId, ep.declaredByPluginName),
            Breadcrumb.Segment(ep.name, icon = Breadcrumb.EP_ICON),
        )
        return wrap(crumbs, pageHeader(ep.name, subtitle = "Extension point", chips = chips), form.build())
    }

    // ---------- Extension ----------

    private fun renderExtension(e: ExtensionInfo): JComponent {
        val title = e.effectiveClass ?: e.implementationClass ?: "(no impl class)"
        val form = DetailForm()
            .row("Extension point", epLink(e.extensionPointName))
            .row("Implementation", FqnLink.render(project, e.effectiveClass ?: e.implementationClass))

        if (e.effectiveClass != null && e.implementationClass != null &&
            e.effectiveClass != e.implementationClass
        ) {
            form.row("Bean class", FqnLink.render(project, e.implementationClass))
        }
        form.row("Provided by", pluginLink(e.providedByPluginId, e.providedByPluginName))

        if (e.additionalAttributes.isNotEmpty()) {
            form.section("XML attributes (${e.additionalAttributes.size})")
            for ((k, v) in e.additionalAttributes.entries.sortedBy { it.key }) {
                form.row(k, copyableMonospace(v))
            }
        }

        // Members preview — uses JavaPsiFacade reflectively, skips silently on non-Java IDEs.
        // The component owns its "Members" section header so we just append it raw.
        val implFqn = e.effectiveClass ?: e.implementationClass
        implFqn?.let { MembersSection.build(project, it) }?.let { form.custom(it) }

        val simple = title.substringAfterLast('.').substringAfterLast('$')
        val crumbs = Breadcrumb.render(
            pluginSegment(e.providedByPluginId, e.providedByPluginName),
            epSegment(e.extensionPointName),
            Breadcrumb.Segment(simple, icon = Breadcrumb.EXT_ICON),
        )
        return wrap(crumbs, pageHeader(simple, subtitle = title, chips = null), form.build())
    }

    // ---------- Service ----------

    private fun renderService(s: ServiceInfo): JComponent {
        val chips = ChipStrip(buildList<JComponent> {
            add(Chips.forArea(s.area))
            if (s.preload != "FALSE") add(Chip("preload=${s.preload}", Chip.GREEN))
            if (s.overrides) add(Chip("overrides", Chip.ORANGE))
            if (s.source == "light_instantiated") add(Chip("@Service", Chip.BLUE))
            if (s.client != null) add(Chip("client=${s.client}", Chip.GRAY))
            if (s.os != null) add(Chip("os=${s.os}", Chip.GRAY))
        })
        val form = DetailForm()
            .row("Implementation", FqnLink.render(project, s.implementationClass))
        if (s.interfaceClass != null) {
            form.row("Interface", FqnLink.render(project, s.interfaceClass))
        }
        if (s.testServiceImplementation != null) {
            form.row("Test impl", FqnLink.render(project, s.testServiceImplementation))
        }
        if (s.headlessImplementation != null) {
            form.row("Headless impl", FqnLink.render(project, s.headlessImplementation))
        }
        if (s.configurationSchemaKey != null) {
            form.row("Config schema key", copyableMonospace(s.configurationSchemaKey))
        }
        form.row("Provided by", pluginLink(s.providedByPluginId, s.providedByPluginName))

        MembersSection.build(project, s.implementationClass)?.let { form.custom(it) }

        val simple = s.implementationClass.substringAfterLast('.').substringAfterLast('$')
        val crumbs = Breadcrumb.render(
            pluginSegment(s.providedByPluginId, s.providedByPluginName),
            Breadcrumb.Segment("Services", icon = Breadcrumb.EP_ICON),
            Breadcrumb.Segment(simple, icon = Breadcrumb.EXT_ICON),
        )
        return wrap(crumbs, pageHeader(simple, subtitle = s.implementationClass, chips = chips), form.build())
    }

    // ---------- Listener ----------

    private fun renderListener(l: ListenerInfo): JComponent {
        val chips = ChipStrip(buildList<JComponent> {
            add(Chips.forArea(l.area))
            if (!l.activeInTestMode) add(Chip("no-test", Chip.GRAY))
            if (!l.activeInHeadlessMode) add(Chip("no-headless", Chip.GRAY))
            if (l.os != null) add(Chip("os=${l.os}", Chip.GRAY))
        })
        val form = DetailForm()
            .row("Listener", FqnLink.render(project, l.listenerClass))
            .row("Topic", FqnLink.render(project, l.topicClass))
            .row("Provided by", pluginLink(l.providedByPluginId, l.providedByPluginName))

        MembersSection.build(project, l.listenerClass)?.let { form.custom(it) }

        val simple = l.listenerClass.substringAfterLast('.').substringAfterLast('$')
        val crumbs = Breadcrumb.render(
            pluginSegment(l.providedByPluginId, l.providedByPluginName),
            Breadcrumb.Segment("Listeners", icon = Breadcrumb.EP_ICON),
            Breadcrumb.Segment(simple, icon = Breadcrumb.EXT_ICON),
        )
        return wrap(crumbs, pageHeader(simple, subtitle = l.listenerClass, chips = chips), form.build())
    }

    // ---------- Dependency ----------

    private fun renderDependency(d: PluginDependencyInfo): JComponent {
        val chips = ChipStrip(Chips.optional(d.optional))
        val form = DetailForm()
            .row("Plugin id", pluginLink(d.pluginId, null))
        val crumbs = Breadcrumb.render(
            Breadcrumb.Segment(d.pluginId, icon = Breadcrumb.DEP_ICON),
        )
        return wrap(crumbs, pageHeader(d.pluginId, subtitle = "Dependency", chips = chips), form.build())
    }

    // ---------- helpers ----------

    private fun pluginLink(pluginId: String, pluginName: String?): JComponent {
        val text = if (!pluginName.isNullOrBlank() && pluginName != pluginId) "$pluginName ($pluginId)"
        else pluginId
        val nav = navigator ?: return JBLabel(text)
        return actionLink(text) { nav.selectPluginById(pluginId) }
    }

    private fun epLink(epName: String): JComponent {
        val nav = navigator ?: return JBLabel(epName)
        return actionLink(epName) { nav.selectExtensionPointByName(epName) }
    }

    private fun dependencyLink(d: PluginDependencyInfo): JComponent {
        val nav = navigator ?: return JBLabel(d.pluginId)
        return actionLink(d.pluginId) { nav.selectPluginById(d.pluginId) }
    }

    private fun copyableMonospace(text: String): JComponent {
        val label = JBLabel(text).apply {
            font = font.deriveFont(font.size2D).let {
                java.awt.Font(java.awt.Font.MONOSPACED, it.style, it.size)
            }
            toolTipText = "Right-click to copy"
        }
        label.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (javax.swing.SwingUtilities.isRightMouseButton(event)) {
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                }
            }
        })
        return label
    }

    private fun wrap(vararg parts: JComponent): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        for (p in parts) {
            p.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(p)
        }
        return panel
    }

    private fun pluginSegment(pluginId: String, pluginName: String?): Breadcrumb.Segment {
        val text = pluginName?.takeIf { it.isNotBlank() && it != pluginId } ?: pluginId
        return Breadcrumb.Segment(
            text = text,
            icon = Breadcrumb.PLUGIN_ICON,
            onClick = navigator?.let { nav -> { nav.selectPluginById(pluginId) } },
        )
    }

    private fun epSegment(epName: String): Breadcrumb.Segment = Breadcrumb.Segment(
        text = epName,
        icon = Breadcrumb.EP_ICON,
        onClick = navigator?.let { nav -> { nav.selectExtensionPointByName(epName) } },
    )

    private fun emptyState(text: String): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
            isOpaque = false
        }
        panel.add(JBLabel(text).apply { foreground = UIUtil.getLabelInfoForeground() }, BorderLayout.NORTH)
        return panel
    }
}
