package com.github.xepozz.introspectorplugin.tools

import com.github.xepozz.introspectorplugin.core.ComponentRegistry
import com.github.xepozz.introspectorplugin.core.ComponentSerializer
import com.github.xepozz.introspectorplugin.core.ComponentTreeWalker
import com.github.xepozz.introspectorplugin.core.XPathMatcher
import com.github.xepozz.introspectorplugin.model.ComponentInfo
import com.github.xepozz.introspectorplugin.model.ComponentProperty
import com.github.xepozz.introspectorplugin.model.FindComponentsResponse
import com.github.xepozz.introspectorplugin.model.UiTreeResponse
import com.github.xepozz.introspectorplugin.util.onEdtBlocking
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import java.awt.Component
import java.awt.Point
import java.awt.Window
import javax.accessibility.Accessible
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlinx.serialization.Serializable

/**
 * MCP toolset for UI introspection. Methods are exposed by the bundled MCP server's
 * ReflectionToolsProvider — the snake_case method name becomes the tool name when
 * [McpTool.name] is not set. We override `name` to keep the grouped `ui.*` namespace.
 */
class UiInspectorToolset : McpToolset {

    @McpTool(name = "ui.get_tree")
    @McpDescription(
        """Returns the IDE's Swing component tree as JSON (BFS, capped by maxDepth).
Each node carries a stable id ("c_xxxxxxxx") that subsequent calls can reuse for
ui.get_properties or screenshot.capture.

Use rootSelector="tool_window:Project" to scope to a single tool window,
or "frame" / "dialog" to constrain by top-level kind."""
    )
    suspend fun `ui_get_tree`(
        @McpDescription("Max BFS depth") maxDepth: Int = 20,
        @McpDescription("frame | dialog | tool_window:<id> | null") rootSelector: String? = null,
        @McpDescription("Include invisible components") includeInvisible: Boolean = false,
        @McpDescription("Attach property bag to each node") includeProperties: Boolean = true,
        @McpDescription("Truncate property values longer than this") truncatePropertyValueAt: Int = 200,
    ): UiTreeResponse = onEdtBlocking(timeoutMs = 5_000) {
        buildTree(maxDepth, rootSelector, includeInvisible, includeProperties, truncatePropertyValueAt)
    }

    @McpTool(name = "ui.find_by_name")
    @McpDescription(
        """Searches the entire IDE UI tree for components whose name/text/accessibleName/toolTipText
match the query. matchMode = "exact" | "contains" | "regex"."""
    )
    suspend fun `ui_find_by_name`(
        @McpDescription("Search query") query: String,
        @McpDescription("exact | contains | regex") matchMode: String = "contains",
        @McpDescription("Whether matching is case sensitive") caseSensitive: Boolean = false,
        @McpDescription("Fields to search in") searchIn: List<String> = DEFAULT_SEARCH_FIELDS,
        @McpDescription("Max matches to return") limit: Int = 50,
    ): FindComponentsResponse = onEdtBlocking(timeoutMs = 5_000) {
        findByName(query, matchMode, caseSensitive, searchIn, limit)
    }

    @McpTool(name = "ui.find_by_coordinates")
    @McpDescription(
        """Finds the deepest visible component under (x, y). coordinateSpace = "screen" | "frame".
If returnAncestors=true, the response also includes the parent chain up to the root window."""
    )
    suspend fun `ui_find_by_coordinates`(
        @McpDescription("X coordinate") x: Int,
        @McpDescription("Y coordinate") y: Int,
        @McpDescription("screen | frame") coordinateSpace: String = "screen",
        @McpDescription("Include parent chain") returnAncestors: Boolean = true,
    ): FindComponentsResponse = onEdtBlocking(timeoutMs = 5_000) {
        findByCoordinates(x, y, coordinateSpace, returnAncestors)
    }

    @McpTool(name = "ui.find_by_xpath")
    @McpDescription(
        """Finds components by an XPath subset compatible with intellij-ui-test-robot.
Supported axes: /, //, ., *, div. Predicates: [@class=..], [@name=..],
[@accessibleName=..], [@text=..], [@toolTipText=..] joined by 'and'; positional [N] (1-based).
Example: //div[@class='ActionButton' and @text='Run']"""
    )
    suspend fun `ui_find_by_xpath`(
        @McpDescription("XPath expression") xpath: String,
        @McpDescription("Max matches to return") limit: Int = 50,
    ): FindComponentsResponse = onEdtBlocking(timeoutMs = 5_000) {
        findByXPath(xpath, limit)
    }

    @McpTool(name = "ui.get_properties")
    @McpDescription(
        """Returns the full property bag for a previously-located component by its id
(issue ui.find_by_* or ui.get_tree first to obtain ids). Includes UI-Inspector-style
PropertyBeans, accessible context, and client properties."""
    )
    suspend fun `ui_get_properties`(
        @McpDescription("Component id from a prior ui.* call") componentId: String,
        @McpDescription("Include JComponent client properties") includeClientProperties: Boolean = true,
        @McpDescription("Include accessible context info") includeAccessibleContext: Boolean = true,
    ): PropertiesResponse {
        val component = ComponentRegistry.getInstance().lookup(componentId)
            ?: throw com.intellij.mcpserver.McpExpectedError(
                "Component '$componentId' is no longer attached", kotlinx.serialization.json.JsonObject(emptyMap())
            )
        return onEdtBlocking(timeoutMs = 5_000) {
            collectProperties(componentId, component, includeClientProperties, includeAccessibleContext)
        }
    }

    @Serializable
    data class PropertiesResponse(
        val componentId: String,
        val className: String,
        val properties: List<ComponentProperty>,
        val warnings: List<String> = emptyList(),
    )

    // -------------------- core implementations --------------------

    private fun buildTree(
        maxDepth: Int,
        rootSelector: String?,
        includeInvisible: Boolean,
        includeProperties: Boolean,
        truncatePropertyValueAt: Int,
    ): UiTreeResponse {
        val registry = ComponentRegistry.getInstance()
        val roots = ComponentTreeWalker.collectRoots(rootSelector)
        val nodes = LinkedHashMap<String, ComponentInfo>()
        val warnings = mutableListOf<String>()
        var truncated = false
        val hardCap = 5_000

        for (root in roots) {
            ComponentTreeWalker.walk(root, maxDepth, includeInvisible) { c, _ ->
                if (nodes.size >= hardCap) { truncated = true; return@walk false }
                val info = ComponentSerializer.toInfo(
                    component = c,
                    registry = registry,
                    includeProperties = includeProperties,
                    truncatePropertyValueAt = truncatePropertyValueAt,
                )
                nodes[info.id] = info
                true
            }
            if (truncated) break
        }
        if (truncated) {
            warnings.add("Tree truncated at hardCap=$hardCap nodes. Narrow rootSelector or lower maxDepth.")
        }
        val rootIds = roots.map { registry.register(it) }
        return UiTreeResponse(
            nodes = nodes.values.toList(),
            rootIds = rootIds,
            truncated = truncated,
            warnings = warnings,
        )
    }

    private fun findByName(
        query: String,
        matchMode: String,
        caseSensitive: Boolean,
        searchIn: List<String>,
        limit: Int,
    ): FindComponentsResponse {
        val registry = ComponentRegistry.getInstance()
        val matches = mutableListOf<ComponentInfo>()
        val seen = HashSet<Component>()
        val match: (String) -> Boolean = when (matchMode) {
            "exact" -> { v -> if (caseSensitive) v == query else v.equals(query, ignoreCase = true) }
            "regex" -> {
                val re = Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE));
                { v -> re.containsMatchIn(v) }
            }
            else -> { v -> v.contains(query, ignoreCase = !caseSensitive) }
        }
        for (root in ComponentTreeWalker.collectRoots(null)) {
            ComponentTreeWalker.walk(root, maxDepth = 50, includeInvisible = false) { c, _ ->
                if (c in seen) return@walk true
                seen.add(c)
                if (collectFields(c, searchIn).any(match)) {
                    matches.add(ComponentSerializer.toInfo(c, registry, true, 200))
                    if (matches.size >= limit) return@walk false
                }
                true
            }
            if (matches.size >= limit) break
        }
        return FindComponentsResponse(matches, matches.size)
    }

    private fun collectFields(c: Component, searchIn: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (field in searchIn) {
            when (field) {
                "name" -> c.name?.let(out::add)
                "text" -> textOf(c)?.let(out::add)
                "accessibleName" -> (c as? Accessible)?.accessibleContext?.accessibleName?.let(out::add)
                "toolTipText" -> (c as? JComponent)?.toolTipText?.let(out::add)
            }
        }
        return out
    }

    private fun textOf(c: Component): String? = when (c) {
        is AbstractButton -> c.text
        is JLabel -> c.text
        else -> null
    }

    private fun findByCoordinates(x: Int, y: Int, space: String, returnAncestors: Boolean): FindComponentsResponse {
        val registry = ComponentRegistry.getInstance()
        val deepest = locateDeepest(x, y, space) ?: return FindComponentsResponse(emptyList(), 0)
        val chain = if (returnAncestors) listOf(deepest) + ComponentTreeWalker.ancestors(deepest) else listOf(deepest)
        val infos = chain.map { ComponentSerializer.toInfo(it, registry, true, 200) }
        return FindComponentsResponse(infos, infos.size)
    }

    private fun locateDeepest(x: Int, y: Int, space: String): Component? {
        val windows = Window.getWindows().filter { it.isShowing }
        for (w in windows) {
            val pt = if (space == "screen") {
                val rel = Point(x, y); SwingUtilities.convertPointFromScreen(rel, w); rel
            } else {
                Point(x, y)
            }
            val bounds = w.bounds
            val within = if (space == "screen") {
                x in bounds.x..(bounds.x + bounds.width) && y in bounds.y..(bounds.y + bounds.height)
            } else {
                pt.x in 0..bounds.width && pt.y in 0..bounds.height
            }
            if (!within) continue
            val deepest = SwingUtilities.getDeepestComponentAt(w, pt.x, pt.y)
            if (deepest != null) return deepest
        }
        return null
    }

    private fun findByXPath(xpath: String, limit: Int): FindComponentsResponse {
        val registry = ComponentRegistry.getInstance()
        val roots = ComponentTreeWalker.collectRoots(null)
        val nodes = LinkedHashMap<String, ComponentInfo>()
        val rootIds = mutableListOf<String>()
        for (root in roots) {
            ComponentTreeWalker.walk(root, maxDepth = 50, includeInvisible = false) { c, _ ->
                if (nodes.size >= 8_000) return@walk false
                val info = ComponentSerializer.toInfo(c, registry, includeProperties = false, truncatePropertyValueAt = 200)
                nodes[info.id] = info
                true
            }
            rootIds.add(registry.register(root))
        }
        val matches = XPathMatcher(nodes, rootIds).query(xpath, limit)
        return FindComponentsResponse(matches, matches.size)
    }

    private fun collectProperties(
        componentId: String,
        component: Component,
        includeClientProperties: Boolean,
        includeAccessibleContext: Boolean,
    ): PropertiesResponse {
        val props = mutableListOf<ComponentProperty>()
        props.add(ComponentProperty("class", component.javaClass.name))
        component.name?.let { props.add(ComponentProperty("name", it)) }
        props.add(ComponentProperty("visible", component.isVisible.toString()))
        props.add(ComponentProperty("enabled", component.isEnabled.toString()))
        val b = component.bounds
        props.add(ComponentProperty("bounds", "${b.x},${b.y} ${b.width}x${b.height}"))
        runCatching { component.locationOnScreen }.getOrNull()?.let {
            props.add(ComponentProperty("locationOnScreen", "${it.x},${it.y}"))
        }
        if (includeClientProperties && component is JComponent) {
            listOf(
                "JComponent.sizeVariant", "place", "action",
                "html.disable", "ActionToolbar.smallVariant",
            ).forEach { k ->
                component.getClientProperty(k)?.let { props.add(ComponentProperty("clientProperty[$k]", it.toString())) }
            }
        }
        if (includeAccessibleContext) {
            (component as? Accessible)?.accessibleContext?.let { ac ->
                ac.accessibleName?.let { props.add(ComponentProperty("accessibleName", it)) }
                ac.accessibleDescription?.let { props.add(ComponentProperty("accessibleDescription", it)) }
                runCatching { ac.accessibleRole?.toString() }.getOrNull()
                    ?.let { props.add(ComponentProperty("accessibleRole", it)) }
            }
        }
        val warnings = mutableListOf<String>()
        try {
            val provider = Class.forName("com.intellij.internal.inspector.UiInspectorContextProvider")
            if (provider.isInstance(component)) {
                val method = provider.getMethod("getUiInspectorContext")
                val beans = method.invoke(component) as? List<*>
                beans?.forEach { bean ->
                    if (bean == null) return@forEach
                    val n = bean.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                        ?.invoke(bean)?.toString() ?: return@forEach
                    val v = bean.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                        ?.invoke(bean)?.toString() ?: ""
                    props.add(ComponentProperty("uiInspector[$n]", v))
                }
            }
        } catch (_: ClassNotFoundException) {
            warnings.add("UiInspectorContextProvider unavailable in this build")
        } catch (t: Throwable) {
            warnings.add("UiInspector reflection failed: ${t.message}")
        }
        return PropertiesResponse(componentId, component.javaClass.name, props, warnings)
    }

    companion object {
        val DEFAULT_SEARCH_FIELDS = listOf("name", "text", "accessibleName", "toolTipText")
    }
}
