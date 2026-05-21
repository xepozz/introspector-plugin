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
        // IMPORTANT: never call ep.extensionList here — it instantiates every extension and
        // surfaces latent registration bugs in other plugins (e.g. com.intellij.java's
        // BuildManager$BuildManagerStartupActivity may not implement ProjectActivity in some
        // builds, which makes the extensionList getter throw and pollute the IDE state).
        // ep.size() returns the adapter count without instantiation.
        val extCount = try { ep.size() } catch (_: Throwable) { 0 }
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
                    ?: readField(adapter, "implementationClassOrName")?.toString()
                    ?: readMethod(adapter, "getOrderId")?.toString()
                // ExtensionComponentAdapter exposes `pluginDescriptor` as a public field, not a getter.
                val pd = readField(adapter, "pluginDescriptor")
                    ?: readMethod(adapter, "getPluginDescriptor")
                val pluginId = pd?.let { extractPluginIdString(it) } ?: "unknown"
                val pluginName = pd?.let { readMethod(it, "getName")?.toString() }
                val attributes = readAdditionalAttributes(adapter)
                val effectiveClass = pickEffectiveClass(implClass, attributes)
                out += ExtensionInfo(
                    extensionPointName = pointName,
                    implementationClass = implClass,
                    effectiveClass = effectiveClass,
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

    /** Walks the class hierarchy looking for a field, honoring superclasses. */
    private fun readField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                return try {
                    f.isAccessible = true
                    f.get(target)
                } catch (_: Throwable) {
                    null
                }
            }
            c = c.superclass
        }
        return null
    }

    /** Pulls the idString out of a PluginDescriptor's PluginId — handles both 'idString' field and toString(). */
    private fun extractPluginIdString(pd: Any): String? {
        val pidObj = readMethod(pd, "getPluginId") ?: readField(pd, "pluginId") ?: return null
        // PluginId#toString() returns idString in modern builds, but cover both paths.
        return readMethod(pidObj, "getIdString")?.toString()
            ?: readField(pidObj, "idString")?.toString()
            ?: pidObj.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readAdditionalAttributes(adapter: Any): Map<String, String> {
        // Two complementary sources for the XML attributes attached to an extension:
        //   1. `extensionElement: XmlElement` — present until IntelliJ nulls it after instance creation
        //      (which is most of the time for services / tool windows by the time we look).
        //   2. `extensionInstance` — when present, public/JvmField properties of the bean are a
        //      lossless mirror of the original XML attributes.
        // We merge both so newly-loaded EPs and long-lived ones look the same.
        val merged = mutableMapOf<String, String>()
        try {
            val element = readField(adapter, "extensionElement")
            if (element != null) {
                val asMap = readField(element, "attributes") as? Map<*, *>
                    ?: readMethod(element, "getAttributes") as? Map<*, *>
                if (asMap != null) {
                    for ((k, v) in asMap) {
                        if (k != null && v != null) merged[k.toString()] = v.toString()
                    }
                } else {
                    val asList = readMethod(element, "getAttributes") as? List<*>
                    asList?.forEach { a ->
                        if (a == null) return@forEach
                        val n = readMethod(a, "getName")?.toString() ?: return@forEach
                        val v = readMethod(a, "getValue")?.toString() ?: return@forEach
                        merged[n] = v
                    }
                }
            }
        } catch (_: Throwable) {}
        try {
            val instance = readField(adapter, "extensionInstance")
            if (instance != null) {
                harvestBeanFields(instance, merged)
            }
        } catch (_: Throwable) {}
        return merged
    }

    /** For BEAN_CLASS extensions whose XmlElement was already discarded, pull XML-attribute-shaped
     *  data straight off the bean's public/JvmField properties. */
    private fun harvestBeanFields(instance: Any, into: MutableMap<String, String>) {
        var c: Class<*>? = instance.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.isSynthetic) continue
                if (into.containsKey(f.name)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(instance) ?: continue
                    // Only flatten primitive-ish values; nested beans would clutter the view.
                    when (v) {
                        is String, is Number, is Boolean, is Char -> into[f.name] = v.toString()
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
    }

    /** Resolve the "user class" for an extension. For INTERFACE EPs, [implClass] is already the
     *  user's class. For BEAN_CLASS EPs, the user's class lives in one of these XML attributes. */
    private fun pickEffectiveClass(implClass: String?, attributes: Map<String, String>): String? {
        val candidates = listOf(
            "implementation", "factoryClass", "instance",
            "serviceImplementation", "serviceInterface", "class",
        )
        for (key in candidates) {
            val v = attributes[key]
            if (!v.isNullOrBlank()) return v
        }
        return implClass
    }
}
