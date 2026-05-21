package com.github.xepozz.ide.introspector.core.internal

/**
 * Pure helpers extracted from ExtensionPointInspector for unit testing.
 * No IDE dependencies — everything works against plain Java reflection / Map data.
 */
internal object ExtensionMetadata {

    /**
     * Resolves the "user class" of an extension. For INTERFACE-kind EPs, [implClass] is
     * already the user's class; for BEAN_CLASS EPs the user class lives in one of these
     * XML attributes — checked in priority order.
     */
    fun pickEffectiveClass(implClass: String?, attributes: Map<String, String>): String? {
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

    /**
     * For BEAN_CLASS extensions whose XmlElement was already discarded, pull
     * XML-attribute-shaped data straight off the bean's public/JvmField properties.
     * Walks the class hierarchy stopping at Object. Skips static and synthetic fields.
     */
    fun harvestBeanFields(instance: Any, into: MutableMap<String, String>) {
        var c: Class<*>? = instance.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.isSynthetic) continue
                if (into.containsKey(f.name)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(instance) ?: continue
                    when (v) {
                        is String, is Number, is Boolean, is Char -> into[f.name] = v.toString()
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
    }
}
