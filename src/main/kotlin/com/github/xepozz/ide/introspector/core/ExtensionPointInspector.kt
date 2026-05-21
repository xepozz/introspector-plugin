package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.core.internal.ExtensionMetadata
import com.github.xepozz.ide.introspector.model.ExtensionInfo
import com.github.xepozz.ide.introspector.model.ExtensionPointInfo
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
    internal fun extractAllEps(area: ExtensionsArea): List<ExtensionPoint<Any>> {
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

    internal fun extensionPointInfoOf(ep: ExtensionPoint<*>, areaTag: String): ExtensionPointInfo {
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
    internal fun epName(ep: ExtensionPoint<*>): String = try {
        val nameField = ep.javaClass.fields.firstOrNull { it.name == "name" }
        nameField?.get(ep)?.toString()
            ?: ep.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(ep)?.toString()
            ?: ep.javaClass.simpleName
    } catch (_: Throwable) {
        ep.javaClass.simpleName
    }

    internal fun kindAndClass(ep: ExtensionPoint<*>): Pair<String, String> {
        // ExtensionPointImpl exposes `className` as a @JvmField; `getKind()` is a method. We
        // try the typed field first (fast), then fall back to reflection across name drift
        // (`className` / `myClassName`) and finally to the lazily-resolved Class<*> for EPs
        // that were registered with a Class reference rather than a name string.
        try {
            val impl = ep as? ExtensionPointImpl<*>
            val typedClassName = impl?.className
            val kindRaw = ep.javaClass.methods.firstOrNull {
                it.name == "getKind" && it.parameterCount == 0
            }?.invoke(ep)
            val kindStr = when (kindRaw?.toString()) {
                "INTERFACE" -> "INTERFACE"
                "BEAN_CLASS" -> "BEAN_CLASS"
                else -> kindRaw?.toString() ?: "BEAN_CLASS"
            }
            val resolvedName = typedClassName
                ?: tryReadClassNameField(ep)
                ?: tryReadExtensionClass(ep)
                ?: "?"
            return kindStr to resolvedName
        } catch (_: Throwable) {
            return "BEAN_CLASS" to "?"
        }
    }

    /** Last-resort: walk declared fields named "className" / "myClassName" — covers shape drift
     *  across platform versions where the public accessor is removed or renamed. */
    internal fun tryReadClassNameField(ep: ExtensionPoint<*>): String? {
        val names = arrayOf("className", "myClassName")
        var cls: Class<*>? = ep.javaClass
        while (cls != null && cls != Any::class.java) {
            for (n in names) {
                val f = cls.declaredFields.firstOrNull { it.name == n } ?: continue
                return try {
                    f.isAccessible = true
                    f.get(ep) as? String
                } catch (_: Throwable) { null }
            }
            cls = cls.superclass
        }
        return null
    }

    /** Reads the lazily-resolved Class<*> off ExtensionPointImpl. Does NOT instantiate any
     *  extension — only forces classloading of the bean/interface type, which the platform
     *  has already done for any EP that has at least one registered extension. */
    internal fun tryReadExtensionClass(ep: ExtensionPoint<*>): String? {
        return try {
            val m = ep.javaClass.methods.firstOrNull {
                it.name == "getExtensionClass" && it.parameterCount == 0
            } ?: return null
            (m.invoke(ep) as? Class<*>)?.name
        } catch (_: Throwable) {
            null
        }
    }

    internal fun pluginDescriptorOf(ep: ExtensionPoint<*>): Pair<String, String?>? {
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

    internal fun isDynamic(ep: ExtensionPoint<*>): Boolean {
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
                val effectiveClass = ExtensionMetadata.pickEffectiveClass(implClass, attributes)
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

    internal fun readMethod(target: Any, name: String): Any? = try {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        m?.invoke(target)
    } catch (_: Throwable) {
        null
    }

    /** Walks the class hierarchy looking for a field, honoring superclasses. */
    internal fun readField(target: Any, name: String): Any? {
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
    internal fun extractPluginIdString(pd: Any): String? {
        val pidObj = readMethod(pd, "getPluginId") ?: readField(pd, "pluginId") ?: return null
        // PluginId#toString() returns idString in modern builds, but cover both paths.
        return readMethod(pidObj, "getIdString")?.toString()
            ?: readField(pidObj, "idString")?.toString()
            ?: pidObj.toString()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun readAdditionalAttributes(adapter: Any): Map<String, String> {
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
                ExtensionMetadata.harvestBeanFields(instance, merged)
            }
        } catch (_: Throwable) {}
        return merged
    }
}
