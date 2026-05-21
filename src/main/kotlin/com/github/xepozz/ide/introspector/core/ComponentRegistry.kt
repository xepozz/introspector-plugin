package com.github.xepozz.ide.introspector.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.awt.Component
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Assigns short, stable IDs to Swing components so that subsequent MCP calls
 * (`ui.get_properties`, `screenshot.capture` with `componentId`) can find the same instance.
 *
 * IDs are not stable across IDE restarts. If GC collects the WeakReference, the ID becomes
 * invalid and lookup returns null — callers should respond with a clear error.
 */
@Service(Service.Level.APP)
class ComponentRegistry {

    private val componentToId = WeakHashMap<Component, String>()
    private val idToRef = HashMap<String, WeakReference<Component>>()

    @Synchronized
    fun register(component: Component): String {
        componentToId[component]?.let { existing ->
            // Re-register in idToRef in case the previous weak reference was cleared.
            idToRef[existing] = WeakReference(component)
            return existing
        }
        val id = "c_" + Integer.toHexString(System.identityHashCode(component)).padStart(8, '0')
        componentToId[component] = id
        idToRef[id] = WeakReference(component)
        return id
    }

    @Synchronized
    fun lookup(id: String): Component? {
        val ref = idToRef[id] ?: return null
        val c = ref.get()
        if (c == null) {
            idToRef.remove(id)
        }
        return c
    }

    /** Drops dead WeakReferences. Cheap, called occasionally. */
    @Synchronized
    fun compact() {
        val dead = idToRef.entries.filter { it.value.get() == null }.map { it.key }
        dead.forEach { idToRef.remove(it) }
    }

    companion object {
        fun getInstance(): ComponentRegistry = service()
    }
}
