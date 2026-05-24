package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.ListenerInfo
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Reads `<applicationListeners>` and `<projectListeners>` declarations off the live
 * [com.intellij.ide.plugins.IdeaPluginDescriptorImpl.appContainerDescriptor] /
 * `projectContainerDescriptor`. Uses reflection because both [com.intellij.util.messages.ListenerDescriptor]
 * and the container class are `@ApiStatus.Internal`.
 *
 * Runtime `messageBus.connect().subscribe(...)` subscribers are out of scope — they're attached
 * imperatively, not declaratively, and the platform doesn't expose a deterministic enumeration.
 */
object ListenerInspector {

    fun listAll(): List<ListenerInfo> {
        val out = mutableListOf<ListenerInfo>()
        for (descriptor in PluginManagerCore.plugins) {
            collectFor(descriptor, out)
        }
        return out
    }

    fun listForPlugin(descriptor: IdeaPluginDescriptor): List<ListenerInfo> {
        val out = mutableListOf<ListenerInfo>()
        collectFor(descriptor, out)
        return out
    }

    private fun collectFor(descriptor: IdeaPluginDescriptor, out: MutableList<ListenerInfo>) {
        val pluginId = descriptor.pluginId.idString
        val pluginName = descriptor.name
        for ((areaTag, getter) in AREA_GETTERS) {
            val container = readContainer(descriptor, getter) ?: continue
            val listeners = readListenerList(container)
            for (ld in listeners) {
                out += try {
                    toListenerInfo(ld, areaTag, pluginId, pluginName)
                } catch (t: Throwable) {
                    thisLogger().debug("Failed to read ListenerDescriptor for $pluginId/$areaTag", t)
                    null
                } ?: continue
            }
        }
    }

    internal fun toListenerInfo(
        ld: Any,
        areaTag: String,
        pluginId: String,
        pluginName: String?,
    ): ListenerInfo? {
        val listenerClass = readField(ld, "listenerClassName")?.toString() ?: return null
        val topicClass = readField(ld, "topicClassName")?.toString() ?: return null
        val activeInTest = (readField(ld, "activeInTestMode") as? Boolean) ?: true
        val activeInHeadless = (readField(ld, "activeInHeadlessMode") as? Boolean) ?: true
        val os = readField(ld, "os")?.let { runCatching { (it as Enum<*>).name }.getOrNull() }
        return ListenerInfo(
            topicClass = topicClass,
            listenerClass = listenerClass,
            area = areaTag,
            activeInTestMode = activeInTest,
            activeInHeadlessMode = activeInHeadless,
            os = os,
            providedByPluginId = pluginId,
            providedByPluginName = pluginName,
        )
    }

    private fun readContainer(descriptor: IdeaPluginDescriptor, getterName: String): Any? {
        val m = descriptor.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterCount == 0
        } ?: return null
        return try { m.invoke(descriptor) } catch (_: Throwable) { null }
    }

    private fun readListenerList(container: Any): List<Any> {
        // ContainerDescriptor.listeners is a @JvmField — public field on the JVM.
        val field = container.javaClass.fields.firstOrNull { it.name == "listeners" }
            ?: return emptyList()
        val raw = try { field.get(container) } catch (_: Throwable) { return emptyList() }
        return (raw as? List<*>)?.filterNotNull().orEmpty()
    }

    private fun readField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                return try {
                    f.isAccessible = true
                    f.get(target)
                } catch (_: Throwable) { null }
            }
            c = c.superclass
        }
        return null
    }

    private val AREA_GETTERS = listOf(
        "application" to "getAppContainerDescriptor",
        "project" to "getProjectContainerDescriptor",
    )
}
