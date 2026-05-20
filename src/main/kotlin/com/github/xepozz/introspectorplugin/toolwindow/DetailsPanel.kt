package com.github.xepozz.introspectorplugin.toolwindow

import com.intellij.openapi.ui.JBMenuItem
import com.intellij.ui.components.JBScrollPane
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.text.html.HTMLEditorKit
import java.awt.BorderLayout

/**
 * Right-side details panel rendering selected-node attributes as HTML. Lightweight to keep
 * the tool window snappy; complex content (long extension lists) is summarised.
 */
class DetailsPanel : JPanel(BorderLayout()) {
    private val editor = JEditorPane().apply {
        editorKit = HTMLEditorKit()
        isEditable = false
        contentType = "text/html"
    }

    init {
        add(JBScrollPane(editor), BorderLayout.CENTER)
        showHtml("<p style='color:gray;font-family:sans-serif'>Select a node to see its details.</p>")
    }

    fun showHtml(html: String) {
        editor.text = "<html><body style='font-family:sans-serif;font-size:11pt'>$html</body></html>"
        editor.caretPosition = 0
    }

    fun render(node: PlatformExplorerNode) {
        when (node) {
            is PlatformExplorerNode.PluginNode -> {
                val p = node.plugin
                showHtml(
                    """
                    <h3>${esc(p.name)}</h3>
                    <table>
                      <tr><td><b>id</b></td><td>${esc(p.id)}</td></tr>
                      <tr><td><b>version</b></td><td>${esc(p.version ?: "?")}</td></tr>
                      <tr><td><b>vendor</b></td><td>${esc(p.vendor ?: "?")}</td></tr>
                      <tr><td><b>bundled</b></td><td>${p.isBundled}</td></tr>
                      <tr><td><b>enabled</b></td><td>${p.isEnabled}</td></tr>
                      <tr><td><b>sinceBuild</b></td><td>${esc(p.sinceBuild ?: "")}</td></tr>
                      <tr><td><b>untilBuild</b></td><td>${esc(p.untilBuild ?: "")}</td></tr>
                      <tr><td><b>declared EPs</b></td><td>${p.declaredExtensionPointsCount}</td></tr>
                      <tr><td><b>dependencies</b></td><td>${p.dependencies.joinToString(", ") { esc(it.pluginId) }}</td></tr>
                    </table>
                    """.trimIndent()
                )
            }
            is PlatformExplorerNode.ExtensionPointNode -> {
                val ep = node.ep
                showHtml(
                    """
                    <h3>${esc(ep.name)}</h3>
                    <table>
                      <tr><td><b>kind</b></td><td>${ep.kind}</td></tr>
                      <tr><td><b>class</b></td><td>${esc(ep.interfaceOrBeanClass)}</td></tr>
                      <tr><td><b>declared by</b></td><td>${esc(ep.declaredByPluginId)} (${esc(ep.declaredByPluginName ?: "")})</td></tr>
                      <tr><td><b>area</b></td><td>${ep.area}</td></tr>
                      <tr><td><b>dynamic</b></td><td>${ep.isDynamic}</td></tr>
                      <tr><td><b>extensions</b></td><td>${ep.extensionsCount}</td></tr>
                    </table>
                    <p><a href='https://plugins.jetbrains.com/intellij-platform-explorer/extensions?extensions=${esc(ep.name)}'>
                       Open in Platform Explorer (web)</a></p>
                    """.trimIndent()
                )
            }
            is PlatformExplorerNode.ExtensionNode -> {
                val e = node.extension
                val attrs = e.additionalAttributes.entries.joinToString("") {
                    "<tr><td><b>${esc(it.key)}</b></td><td>${esc(it.value)}</td></tr>"
                }
                showHtml(
                    """
                    <h3>${esc(e.implementationClass ?: "(no impl class)")}</h3>
                    <table>
                      <tr><td><b>EP</b></td><td>${esc(e.extensionPointName)}</td></tr>
                      <tr><td><b>provided by</b></td><td>${esc(e.providedByPluginId)}</td></tr>
                      $attrs
                    </table>
                    """.trimIndent()
                )
            }
            is PlatformExplorerNode.DependencyNode -> {
                val d = node.dep
                showHtml("<h3>${esc(d.pluginId)}</h3><p>${if (d.optional) "optional" else "required"}</p>")
            }
            is PlatformExplorerNode.GroupNode -> showHtml("<h3>${esc(node.displayName)}</h3><p>${node.count} items.</p>")
            is PlatformExplorerNode.Root -> showHtml("<p>Choose a plugin or extension point on the left.</p>")
            is PlatformExplorerNode.LoadingNode -> showHtml("<p>${esc(node.displayName)}</p>")
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
