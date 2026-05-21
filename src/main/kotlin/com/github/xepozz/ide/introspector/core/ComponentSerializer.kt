package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.Bounds
import com.github.xepozz.ide.introspector.model.ComponentInfo
import com.github.xepozz.ide.introspector.model.ComponentProperty
import java.awt.Component
import java.awt.Container
import javax.accessibility.Accessible
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.text.JTextComponent

/**
 * Converts a live Swing [Component] into a serialisable [ComponentInfo].
 * Must be called on the EDT.
 */
object ComponentSerializer {

    fun toInfo(
        component: Component,
        registry: ComponentRegistry,
        includeProperties: Boolean,
        truncatePropertyValueAt: Int,
        childIds: List<String> = collectChildIds(component, registry),
    ): ComponentInfo {
        val id = registry.register(component)
        val accessible = (component as? Accessible)?.accessibleContext
        val text = extractText(component)
        val toolTip = (component as? JComponent)?.toolTipText
        val bounds = try {
            val b = component.bounds
            Bounds(b.x, b.y, b.width, b.height)
        } catch (_: Throwable) {
            Bounds(0, 0, 0, 0)
        }

        val props = if (includeProperties) collectProperties(component, truncatePropertyValueAt) else emptyList()

        return ComponentInfo(
            id = id,
            className = component.javaClass.name,
            name = component.name,
            accessibleName = accessible?.accessibleName,
            accessibleRole = accessible?.accessibleRole?.toString(),
            bounds = bounds,
            visible = component.isVisible,
            enabled = component.isEnabled,
            text = text,
            toolTipText = toolTip,
            properties = props,
            children = childIds,
        )
    }

    private fun collectChildIds(component: Component, registry: ComponentRegistry): List<String> {
        if (component !is Container) return emptyList()
        return component.components.filterNotNull().map { registry.register(it) }
    }

    private fun extractText(component: Component): String? = when (component) {
        is AbstractButton -> component.text
        is JLabel -> component.text
        is JTextComponent -> {
            val t = try { component.text } catch (_: Throwable) { null }
            if (t != null && t.length > 500) t.substring(0, 500) + "…" else t
        }
        else -> null
    }

    private fun collectProperties(component: Component, truncateAt: Int): List<ComponentProperty> {
        val out = mutableListOf<ComponentProperty>()

        // Visible client properties — JComponent stores plenty under named keys.
        // We probe a small fixed set rather than enumerating the whole clientProperties table:
        // the table is an ArrayTable in some versions and a Hashtable in others, with no stable
        // public iteration API. The public getClientProperty(key) covers everything we care about.
        if (component is JComponent) {
            WELL_KNOWN_CLIENT_PROPERTY_KEYS.forEach { key ->
                val v = component.getClientProperty(key)
                if (v != null) out.add(ComponentProperty(key, truncate(v.toString(), truncateAt)))
            }
        }

        // For ActionButton subclasses we want to know which AnAction they represent.
        try {
            val cls = component.javaClass
            if (cls.name.contains("ActionButton")) {
                val method = cls.methods.firstOrNull { it.name == "getAction" && it.parameterCount == 0 }
                val action = method?.invoke(component)
                if (action != null) {
                    out.add(ComponentProperty("action", truncate(action.javaClass.name, truncateAt)))
                }
            }
        } catch (_: Throwable) {
        }

        return out
    }

    private fun truncate(s: String, limit: Int): String {
        if (limit <= 0 || s.length <= limit) return s
        return s.substring(0, limit) + "…"
    }

    private val WELL_KNOWN_CLIENT_PROPERTY_KEYS = listOf(
        "JComponent.sizeVariant",
        "place",
        "action",
        "ActionToolbar.smallVariant",
        "html.disable",
    )
}
