package com.github.xepozz.introspectorplugin.core

import com.github.xepozz.introspectorplugin.model.ExtensionInfo
import com.github.xepozz.introspectorplugin.model.ExtensionPointInfo
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.application.ApplicationManager

/**
 * Reads the live [com.intellij.openapi.extensions.Extensions] graph. EP collection is
 * thread-safe so handlers don't need to bounce on the EDT.
 */
object ExtensionPointInspector {

    /** Returns all EPs in [area] (application/project/both), sorted by name. */
    fun listExtensionPoints(area: String): List<ExtensionPointInfo> {
        val out = mutableListOf<ExtensionPointInfo>()
        if (area == "application" || area == "both") {
            val app = ApplicationManager.getApplication().extensionArea
            out += collectFromArea(app, "application")
        }
        if (area == "project" || area == "both") {
            for (project in ProjectManager.getInstance().openProjects) {
                out += collectFromArea(project.extensionArea, "project")
            }
        }
        return out.sortedBy { it.name }
    }

    private fun collectFromArea(area: ExtensionsArea, areaTag: String): List<ExtensionPointInfo> {
        val out = mutableListOf<ExtensionPointInfo>()
        try {
            val eps: List<ExtensionPoint<Any>> = extractAllEps(area)
            for (ep in eps) {
                out += try {
                    extensionPointInfoOf(ep, areaTag)
                } catch (t: Throwable) {
                    thisLogger().debug("Failed to inspect EP ${epName(ep)}", t)
                    null
                } ?: continue
            }
        } catch (t: Throwable) {
            thisLogger().warn("Failed to enumerate extension points for area=$areaTag", t)
        }
        return out
    }

    /** Reflection-based extraction of all EPs from an [ExtensionsArea]. */
    @Suppress("UNCHECKED_CAST")
    private fun extractAllEps(area: ExtensionsArea): List<ExtensionPoint<Any>> {
        // ExtensionsAreaImpl exposes a method `getExtensionPoints()` returning Map<String, EP>.
        // We use reflection because it isn't in the public API.
        val method = area.javaClass.methods.firstOrNull {
            it.name == "getExtensionPoints" && it.parameterCount == 0
        }
        if (method != null) {
            val value = method.invoke(area)
            if (value is Map<*, *>) {
                return value.values.filterIsInstance<ExtensionPoint<*>>() as List<ExtensionPoint<Any>>
            }
            if (value is Collection<*>) {
                return value.filterIsInstance<ExtensionPoint<*>>() as List<ExtensionPoint<Any>>
            }
        }
        // Fallback: scan the `extensionPoints` field.
        val field = area.javaClass.declaredFields.firstOrNull { it.name == "extensionPoints" }
        if (field != null) {
            field.isAccessible = true
            val v = field.get(area)
            if (v is Map<*, *>) {
                return v.values.filterIsInstance<ExtensionPoint<*>>() as List<ExtensionPoint<Any>>
            }
        }
        return emptyList()
    }

    private fun extensionPointInfoOf(ep: ExtensionPoint<*>, areaTag: String): ExtensionPointInfo {
        val (kind, beanOrInterface) = kindAndClass(ep)
        val pluginDescriptor = pluginDescriptorOf(ep)
        val dynamic = isDynamic(ep)
        val extCount = try { ep.extensionList.size } catch (_: Throwable) { 0 }
        return ExtensionPointInfo(
            name = epName(ep),
            kind = kind,
            interfaceOrBeanClass = beanOrInterface,
            declaredByPluginId = pluginDescriptor?.first ?: "unknown",
            declaredByPluginName = pluginDescriptor?.second,
            isDynamic = dynamic,
            extensionsCount = extCount,
            area = areaTag,
        )
    }

    /** [ExtensionPoint] doesn't expose a `name` on the public interface — it's on the impl. */
    private fun epName(ep: ExtensionPoint<*>): String = try {
        val nameField = ep.javaClass.fields.firstOrNull { it.name == "name" }
        nameField?.get(ep)?.toString()
            ?: ep.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(ep)?.toString()
            ?: ep.javaClass.simpleName
    } catch (_: Throwable) {
        ep.javaClass.simpleName
    }

    private fun kindAndClass(ep: ExtensionPoint<*>): Pair<String, String> {
        // ExtensionPointImpl exposes `getClassName()` / `getKind()`.
        try {
            val classNameMethod = ep.javaClass.methods.firstOrNull { it.name == "getClassName" && it.parameterCount == 0 }
            val kindMethod = ep.javaClass.methods.firstOrNull { it.name == "getKind" && it.parameterCount == 0 }
            val className = classNameMethod?.invoke(ep) as? String ?: "?"
            val kindRaw = kindMethod?.invoke(ep)
            val kindStr = when (kindRaw?.toString()) {
                "INTERFACE" -> "INTERFACE"
                "BEAN_CLASS" -> "BEAN_CLASS"
                else -> kindRaw?.toString() ?: "BEAN_CLASS"
            }
            return kindStr to className
        } catch (_: Throwable) {
            return "BEAN_CLASS" to "?"
        }
    }

    private fun pluginDescriptorOf(ep: ExtensionPoint<*>): Pair<String, String?>? {
        return try {
            val method = ep.javaClass.methods.firstOrNull {
                (it.name == "getPluginDescriptor" || it.name == "getDescriptor") && it.parameterCount == 0
            } ?: return null
            val pd = method.invoke(ep) ?: return null
            val idMethod = pd.javaClass.methods.firstOrNull { it.name == "getPluginId" && it.parameterCount == 0 }
            val nameMethod = pd.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
            val id = idMethod?.invoke(pd)?.toString() ?: "unknown"
            val name = nameMethod?.invoke(pd)?.toString()
            id to name
        } catch (_: Throwable) {
            null
        }
    }

    private fun isDynamic(ep: ExtensionPoint<*>): Boolean {
        return try {
            val m = ep.javaClass.methods.firstOrNull { it.name == "isDynamic" && it.parameterCount == 0 }
            (m?.invoke(ep) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun listExtensionsForEp(name: String, limit: Int): List<ExtensionInfo> {
        val ep = locateEp(name) ?: return emptyList()
        return extensionsOf(ep, name).take(limit)
    }

    private fun locateEp(epName: String): ExtensionPoint<*>? {
        val app = ApplicationManager.getApplication().extensionArea
        try {
            val maybe = app.getExtensionPointIfRegistered<Any>(epName)
            if (maybe != null) return maybe
        } catch (_: Throwable) {
        }
        for (project in ProjectManager.getInstance().openProjects) {
            try {
                val maybe = project.extensionArea.getExtensionPointIfRegistered<Any>(epName)
                if (maybe != null) return maybe
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** For each registered extension instance, produce an [ExtensionInfo]. */
    private fun extensionsOf(ep: ExtensionPoint<*>, pointName: String): List<ExtensionInfo> {
        val out = mutableListOf<ExtensionInfo>()
        try {
            // ExtensionPointImpl#sortedAdapters returns ExtensionComponentAdapter list.
            val adaptersMethod = ep.javaClass.methods.firstOrNull {
                it.name == "getSortedAdapters" && it.parameterCount == 0
            } ?: ep.javaClass.methods.firstOrNull {
                it.name == "sortedAdapters" && it.parameterCount == 0
            }
            val adapters = (adaptersMethod?.invoke(ep) as? List<*>).orEmpty()

            for (adapter in adapters) {
                if (adapter == null) continue
                val implClass = readMethod(adapter, "getAssignableToClassName")?.toString()
                    ?: readMethod(adapter, "getImplementationClassName")?.toString()
                    ?: readMethod(adapter, "getOrderId")?.toString()
                val pd = readMethod(adapter, "getPluginDescriptor")
                val pluginId = pd?.let { readMethod(it, "getPluginId")?.toString() } ?: "unknown"
                val pluginName = pd?.let { readMethod(it, "getName")?.toString() }
                val attributes = readAdditionalAttributes(adapter)
                out += ExtensionInfo(
                    extensionPointName = pointName,
                    implementationClass = implClass,
                    providedByPluginId = pluginId,
                    providedByPluginName = pluginName,
                    additionalAttributes = attributes,
                )
            }
        } catch (t: Throwable) {
            thisLogger().debug("Failed to enumerate extensions for $pointName", t)
        }
        return out
    }

    private fun readMethod(target: Any, name: String): Any? = try {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        m?.invoke(target)
    } catch (_: Throwable) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun readAdditionalAttributes(adapter: Any): Map<String, String> {
        // ExtensionComponentAdapter has private `pluginDescriptor` and (sometimes) `extensionElement`.
        try {
            val field = adapter.javaClass.declaredFields.firstOrNull { it.name == "extensionElement" }
                ?: return emptyMap()
            field.isAccessible = true
            val element = field.get(adapter) ?: return emptyMap()
            // org.jdom.Element / XmlElement: try `getAttributes()`.
            val attrsMethod = element.javaClass.methods.firstOrNull {
                it.name == "getAttributes" && it.parameterCount == 0
            } ?: return emptyMap()
            val attrs = attrsMethod.invoke(element) as? List<*> ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            for (a in attrs) {
                if (a == null) continue
                val n = readMethod(a, "getName")?.toString() ?: continue
                val v = readMethod(a, "getValue")?.toString() ?: continue
                map[n] = v
            }
            return map
        } catch (_: Throwable) {
            return emptyMap()
        }
    }
}
