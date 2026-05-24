package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.core.internal.ExtensionMetadata
import com.github.xepozz.ide.introspector.model.BeanField
import com.github.xepozz.ide.introspector.model.BeanSchema
import com.github.xepozz.ide.introspector.model.ExtensionInfo
import com.github.xepozz.ide.introspector.model.ExtensionPointDetails
import com.github.xepozz.ide.introspector.model.ExtensionPointInfo
import com.github.xepozz.ide.introspector.model.MethodSig
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.application.ApplicationManager
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
        // The 252+ platform dropped the `ExtensionPointImpl.getKind()` accessor; the concrete
        // subclass — `InterfaceExtensionPoint` vs `BeanExtensionPoint` — is the canonical kind
        // signal. Try `getKind()` first for older 251.x builds (and for synthetic test EPs that
        // report custom kinds), then fall back to class-name inspection. Class lookup is via
        // reflection only — `ExtensionPointImpl` is @ApiStatus.Internal and direct usage trips
        // the plugin verifier.
        try {
            val kindRaw = ep.javaClass.methods.firstOrNull {
                it.name == "getKind" && it.parameterCount == 0
            }?.invoke(ep)?.toString()
            val kindStr = when {
                kindRaw != null -> kindRaw   // preserve "INTERFACE" / "BEAN_CLASS" / custom values
                else -> kindFromClassName(ep.javaClass) ?: "BEAN_CLASS"
            }
            val resolvedName = tryReadClassNameField(ep)
                ?: tryReadExtensionClass(ep)
                ?: "?"
            return kindStr to resolvedName
        } catch (_: Throwable) {
            return "BEAN_CLASS" to "?"
        }
    }

    private fun kindFromClassName(cls: Class<*>): String? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val simple = c.simpleName
            if (simple == "InterfaceExtensionPoint") return "INTERFACE"
            if (simple == "BeanExtensionPoint") return "BEAN_CLASS"
            c = c.superclass
        }
        return null
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

    private fun locateEp(epName: String): ExtensionPoint<*>? = locateEpWithArea(epName)?.first

    /** Locates an EP across application + open project areas, returning the EP and its area tag. */
    internal fun locateEpWithArea(epName: String): Pair<ExtensionPoint<*>, String>? {
        val app = ApplicationManager.getApplication().extensionArea
        try {
            val maybe = app.getExtensionPointIfRegistered<Any>(epName)
            if (maybe != null) return maybe to "application"
        } catch (_: Throwable) {
        }
        for (project in ProjectManager.getInstance().openProjects) {
            try {
                val maybe = project.extensionArea.getExtensionPointIfRegistered<Any>(epName)
                if (maybe != null) return maybe to "project"
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Returns the full descriptor for a single EP: kind, declaring plugin, area, dynamic
     * flag, and (per the include* flags) either the bean-class XML schema or the public
     * abstract methods of the interface. Returns null when the EP name isn't registered.
     *
     * Pure-reflection — thread-safe, no EDT bounce, no extension instantiation.
     * The `extensionList` accessor is NEVER touched (CLAUDE.md pitfall); `ep.size()` is
     * used for [includeRegisteredCount] which only walks the adapter list.
     */
    fun getDetails(
        name: String,
        includeBeanSchema: Boolean = true,
        includeInterfaceMethods: Boolean = true,
        includeRegisteredCount: Boolean = false,
        maxFields: Int = 200,
    ): ExtensionPointDetails? {
        val located = locateEpWithArea(name) ?: return null
        val (ep, area) = located
        val (kind, interfaceOrBean) = kindAndClass(ep)
        val pd = pluginDescriptorOf(ep)
        val dynamic = isDynamic(ep)
        val count = if (includeRegisteredCount) {
            // Adapter count only — never extensionList (CLAUDE.md pitfall).
            try { ep.size() } catch (_: Throwable) { 0 }
        } else null

        // Resolve the bean / interface Class<*> from the EP. tryReadExtensionClass uses the
        // platform's own lazily-resolved Class<*>; if absent, fall back to loading via the
        // EP's plugin classloader (so plugin-supplied bean classes resolve even when the
        // application classloader can't see them).
        val cls: Class<*>? = resolveExtensionClass(ep, interfaceOrBean)

        val beanSchema = if (kind == "BEAN_CLASS" && includeBeanSchema && cls != null) {
            runCatching { harvestBeanSchema(cls, maxFields) }.getOrNull()
        } else null

        val interfaceMethods = if (kind == "INTERFACE" && includeInterfaceMethods && cls != null) {
            runCatching { harvestInterfaceMethods(cls, maxFields) }.getOrNull()
        } else null

        return ExtensionPointDetails(
            name = name,
            kind = kind,
            interfaceOrBeanClass = interfaceOrBean,
            declaredByPluginId = pd?.first ?: "unknown",
            declaredByPluginName = pd?.second,
            dynamic = dynamic,
            area = area,
            beanSchema = beanSchema,
            interfaceMethods = interfaceMethods,
            registeredCount = count,
        )
    }

    /**
     * Resolves the EP's extension Class<*> for reflection. Prefers the platform's
     * lazily-resolved `getExtensionClass()` (already loaded for any populated EP), then
     * falls back to loading [fqn] via the EP's plugin classloader, then this classloader.
     * Returns null when nothing works (broken plugin, missing class).
     */
    private fun resolveExtensionClass(ep: ExtensionPoint<*>, fqn: String): Class<*>? {
        try {
            val m = ep.javaClass.methods.firstOrNull {
                it.name == "getExtensionClass" && it.parameterCount == 0
            }
            val resolved = m?.invoke(ep) as? Class<*>
            if (resolved != null) return resolved
        } catch (_: Throwable) {}
        if (fqn == "?" || fqn.isBlank()) return null
        val pdLoader: ClassLoader? = try {
            val getter = ep.javaClass.methods.firstOrNull {
                it.name == "getPluginDescriptor" && it.parameterCount == 0
            }
            val pd = getter?.invoke(ep)
            (pd?.let { readMethod(it, "getPluginClassLoader") } as? ClassLoader)
        } catch (_: Throwable) { null }
        for (loader in listOfNotNull(pdLoader, ExtensionPointInspector::class.java.classLoader)) {
            try {
                return Class.forName(fqn, false, loader)
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Walks [cls] and its superclasses (stopping at Object) and harvests each declared
     * field's XML-binding annotations into a [BeanField]. Mirrors how
     * `com.intellij.util.xmlb.XmlSerializer` decides whether/how to serialize a field:
     *   - `@Attribute("foo")` → xmlAttributeName="foo"; default `field.name` if no value.
     *   - `@Tag("foo")` → xmlTagName="foo"; xmlAttributeName=null (nested element).
     *   - `@Property(style=TAG)` → xmlTagName=field.name (nested element).
     *   - No annotation and public (non-static, non-synthetic) → serialized using field name.
     *   - Private unannotated fields → skipped (XmlSerializer skips them too).
     * `@RequiredElement` → required=true. `@Deprecated` (Java or Kotlin) → deprecated=true.
     * Caps at [maxFields] and reports the cap via [BeanSchema.truncated].
     */
    internal fun harvestBeanSchema(cls: Class<*>, maxFields: Int): BeanSchema {
        val fields = mutableListOf<BeanField>()
        val seen = mutableSetOf<String>() // avoid duplicate field names from class shadowing
        var truncated = false
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.isSynthetic) continue
                if (!seen.add(f.name)) continue
                if (fields.size >= maxFields) { truncated = true; break }
                val mapped = describeBeanField(f) ?: continue
                fields += mapped
            }
            if (fields.size >= maxFields) { truncated = true; break }
            c = c.superclass
        }
        return BeanSchema(cls.name, fields, truncated)
    }

    /**
     * Returns a [BeanField] description for [f] or null if XmlSerializer would skip the
     * field outright (private + no xmlb annotation).
     */
    private fun describeBeanField(f: Field): BeanField? {
        val attrAnn = findAnnotation(f, "com.intellij.util.xmlb.annotations.Attribute")
        val tagAnn = findAnnotation(f, "com.intellij.util.xmlb.annotations.Tag")
        val propAnn = findAnnotation(f, "com.intellij.util.xmlb.annotations.Property")
        // The platform moved @RequiredElement out of `com.intellij.util.xmlb.annotations`
        // and into `com.intellij.openapi.extensions` somewhere around 252. Accept either FQN
        // so the harvester works regardless of which IDE version contributed the bean class.
        val requiredAnn = findAnnotation(f, "com.intellij.openapi.extensions.RequiredElement")
            ?: findAnnotation(f, "com.intellij.util.xmlb.annotations.RequiredElement")

        // Kotlin's @Deprecated targets PROPERTY (not FIELD), so for `@JvmField var x` the
        // annotation lands on the synthetic getter/setter — never on the Field itself. Fall
        // back to the declaring class's matching getter when the field check comes up empty.
        val deprecated = f.isAnnotationPresent(java.lang.Deprecated::class.java) ||
            findAnnotation(f, "kotlin.Deprecated") != null ||
            isPropertyDeprecated(f)

        // Decide xmlAttributeName / xmlTagName.
        var xmlAttribute: String? = null
        var xmlTag: String? = null
        if (attrAnn != null) {
            xmlAttribute = readAnnotationStringValue(attrAnn) ?: f.name
        }
        if (tagAnn != null) {
            xmlTag = readAnnotationStringValue(tagAnn) ?: f.name
            // @Tag overrides — represent as nested element, no attribute name.
            xmlAttribute = null
        }
        if (propAnn != null && tagAnn == null && attrAnn == null) {
            // @Property(style = TAG / ATTRIBUTE). style is an enum; read its name reflectively.
            val style = readAnnotationEnumName(propAnn, "style")
            when (style) {
                "TAG" -> { xmlTag = f.name; xmlAttribute = null }
                "ATTRIBUTE" -> { xmlAttribute = f.name }
                else -> { /* @Property without style hint — keep defaults below */ }
            }
        }
        if (xmlAttribute == null && xmlTag == null) {
            // No xmlb annotation. Public non-static fields serialize using the field name;
            // private unannotated ones are skipped by XmlSerializer.
            if (!Modifier.isPublic(f.modifiers)) return null
            xmlAttribute = f.name
        }

        return BeanField(
            name = f.name,
            xmlAttributeName = xmlAttribute,
            xmlTagName = xmlTag,
            type = f.type.name,
            required = requiredAnn != null,
            defaultValue = readStaticFinalDefault(f),
            deprecated = deprecated,
        )
    }

    /**
     * Returns every public abstract method (excluding java.lang.Object overrides and
     * synthetic / bridge methods) of [cls]. Output is sorted by name for stability.
     */
    internal fun harvestInterfaceMethods(cls: Class<*>, maxFields: Int): List<MethodSig> {
        val out = mutableListOf<MethodSig>()
        val seen = mutableSetOf<String>() // dedupe same name+param signatures
        // Use methods (not declaredMethods) so inherited abstract methods from super-interfaces
        // are also surfaced — INTERFACE EPs frequently extend a common SPI interface.
        for (m in cls.methods.sortedBy { it.name }) {
            if (out.size >= maxFields) break
            if (m.declaringClass == Any::class.java) continue
            if (m.isSynthetic || m.isBridge) continue
            // Surface abstract methods (the implementation contract). For interface EPs
            // these are exactly the methods a contributor MUST implement.
            if (!Modifier.isAbstract(m.modifiers)) continue
            val sig = methodSignatureString(m)
            val key = "${m.name}$sig"
            if (!seen.add(key)) continue
            out += MethodSig(
                name = m.name,
                signature = sig,
                returnType = m.returnType.simpleName,
                deprecated = m.isAnnotationPresent(java.lang.Deprecated::class.java) ||
                    findAnnotation(m, "kotlin.Deprecated") != null,
            )
        }
        return out
    }

    /** Formats a method signature as "(P1, P2): R" using simple type names. */
    private fun methodSignatureString(m: Method): String {
        val params = m.parameterTypes.joinToString(", ") { it.simpleName }
        return "($params): ${m.returnType.simpleName}"
    }

    /**
     * Walks [element]'s annotations and returns the first whose annotation type FQN matches
     * [annotationFqn]. Reflection-only (no compile-time dependency on `intellij.platform`'s
     * xmlb annotations) so this code unit-tests cleanly without the IDE classpath.
     */
    private fun findAnnotation(element: java.lang.reflect.AnnotatedElement, annotationFqn: String): Any? {
        for (ann in element.annotations) {
            if (ann.annotationClass.java.name == annotationFqn) return ann
        }
        return null
    }

    /**
     * Kotlin's `@Deprecated` lands on the property's getter/setter or on a synthetic
     * `$annotations` holder method (Kotlin compiler emits it for `@JvmField` properties
     * whose annotations would otherwise be lost). Walk every declared method on the
     * class — anything that mentions the field name and carries `@Deprecated`/`kotlin.Deprecated`
     * counts.
     */
    private fun isPropertyDeprecated(f: Field): Boolean {
        val cls = f.declaringClass ?: return false
        val cap = f.name.replaceFirstChar { it.uppercase() }
        val candidateNames = setOf(
            "get$cap", "is$cap", "set$cap",
            "${f.name}\$annotations",
            "${f.name}\$annotations\$kotlin",
        )
        for (m in cls.declaredMethods) {
            if (m.name !in candidateNames) continue
            if (m.isAnnotationPresent(java.lang.Deprecated::class.java)) return true
            if (findAnnotation(m, "kotlin.Deprecated") != null) return true
        }
        // Last resort: use kotlin-reflect via KClass.members so we catch @Deprecated declared
        // on the Kotlin property itself even when the JVM field has no carrier annotation.
        return try {
            cls.kotlin.members.any { member ->
                member.name == f.name &&
                    member.annotations.any { it.annotationClass.java.name == "kotlin.Deprecated" }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Reads the `value()` String of an annotation reflectively. Returns null on empty
     * string ("" is the xmlb-annotations default sentinel meaning "use the field name").
     */
    private fun readAnnotationStringValue(annotation: Any): String? {
        return try {
            val m = annotation.javaClass.methods.firstOrNull {
                it.name == "value" && it.parameterCount == 0
            } ?: return null
            val v = m.invoke(annotation) as? String
            if (v.isNullOrEmpty()) null else v
        } catch (_: Throwable) { null }
    }

    /** Reads an enum-typed annotation member by name (e.g. @Property.style) and returns its `.name`. */
    private fun readAnnotationEnumName(annotation: Any, member: String): String? {
        return try {
            val m = annotation.javaClass.methods.firstOrNull {
                it.name == member && it.parameterCount == 0
            } ?: return null
            (m.invoke(annotation) as? Enum<*>)?.name
        } catch (_: Throwable) { null }
    }

    /**
     * Best-effort default-value extraction. Only static-final literal initializers are
     * safe — instance-field initializers compile into `<init>` and reading them would
     * require instantiating the bean, which can transitively touch project services and
     * blow up the same way `extensionList.size` does (CLAUDE.md pitfall).
     */
    private fun readStaticFinalDefault(f: Field): String? {
        if (!Modifier.isStatic(f.modifiers) || !Modifier.isFinal(f.modifiers)) return null
        return try {
            f.isAccessible = true
            f.get(null)?.toString()
        } catch (_: Throwable) { null }
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
