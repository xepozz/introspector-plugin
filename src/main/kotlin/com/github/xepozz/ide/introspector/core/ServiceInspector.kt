package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.ServiceInfo
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.ServiceDescriptor
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import java.lang.reflect.Modifier

/**
 * Reads `<applicationService>` / `<projectService>` / `<moduleService>` declarations off the
 * live [com.intellij.ide.plugins.IdeaPluginDescriptorImpl.appContainerDescriptor] /
 * `projectContainerDescriptor` / `moduleContainerDescriptor`, and (best-effort) enumerates
 * already-instantiated `@Service`-annotated light services via
 * `ComponentManagerEx.processAllImplementationClasses`.
 *
 * Both paths use reflection to avoid compile-time references to `@ApiStatus.Internal` types,
 * keeping plugin-verifier output clean. [ServiceDescriptor] itself is public and accessed directly.
 */
object ServiceInspector {

    /** XML-declared services across every installed plugin, all three areas. Deterministic. */
    fun listAll(): List<ServiceInfo> {
        val out = mutableListOf<ServiceInfo>()
        for (descriptor in PluginManagerCore.plugins) {
            collectFor(descriptor, out)
        }
        return out
    }

    /** XML-declared services for a single plugin. */
    fun listForPlugin(descriptor: IdeaPluginDescriptor): List<ServiceInfo> {
        val out = mutableListOf<ServiceInfo>()
        collectFor(descriptor, out)
        return out
    }

    private fun collectFor(descriptor: IdeaPluginDescriptor, out: MutableList<ServiceInfo>) {
        val pluginId = descriptor.pluginId.idString
        val pluginName = descriptor.name
        for ((areaTag, getter) in AREA_GETTERS) {
            val container = readContainer(descriptor, getter) ?: continue
            val services = readServiceList(container)
            for (sd in services) {
                out += try {
                    toServiceInfo(sd, areaTag, pluginId, pluginName)
                } catch (t: Throwable) {
                    thisLogger().debug("Failed to read ServiceDescriptor for $pluginId/$areaTag", t)
                    null
                } ?: continue
            }
        }
    }

    internal fun toServiceInfo(
        sd: ServiceDescriptor,
        areaTag: String,
        pluginId: String,
        pluginName: String?,
    ): ServiceInfo? {
        val impl = sd.serviceImplementation
            ?: sd.testServiceImplementation
            ?: sd.headlessImplementation
            ?: return null
        return ServiceInfo(
            interfaceClass = sd.serviceInterface,
            implementationClass = impl,
            testServiceImplementation = sd.testServiceImplementation,
            headlessImplementation = sd.headlessImplementation,
            area = areaTag,
            preload = sd.preload.name,
            client = sd.client?.toString(),
            os = sd.os?.name,
            overrides = sd.overrides,
            configurationSchemaKey = sd.configurationSchemaKey,
            providedByPluginId = pluginId,
            providedByPluginName = pluginName,
            source = "xml",
        )
    }

    /**
     * Best-effort enumeration of already-created light services (`@Service`-annotated, registered
     * dynamically by the platform without an XML entry). The result is non-deterministic — it
     * depends on which services have been touched in the running IDE.
     *
     * [excludingImplementations] is the set of `implementationClass` values already collected
     * from XML, so we don't double-count services that happen to be both XML-declared and
     * `@Service`-annotated.
     */
    fun listLightInstantiated(excludingImplementations: Set<String> = emptySet()): List<ServiceInfo> {
        val app = ApplicationManager.getApplication() ?: return emptyList()
        val out = mutableListOf<ServiceInfo>()
        // App-level light services live on the Application; project-level light services live on
        // each open Project (Project also implements ComponentManagerEx). Walk both.
        walkLightServices(app, excludingImplementations, out)
        for (project in ProjectManager.getInstance().openProjects) {
            walkLightServices(project, excludingImplementations, out)
        }
        return out.distinctBy { it.implementationClass }
    }

    private fun walkLightServices(
        componentManager: Any,
        excludingImplementations: Set<String>,
        out: MutableList<ServiceInfo>,
    ) {
        val method = componentManager.javaClass.methods.firstOrNull {
            it.name == "processAllImplementationClasses" && it.parameterCount == 1
        } ?: return
        val callback: (Class<*>, Any?) -> Unit = collector@{ cls, pluginDescriptor ->
            try {
                if (!isLightService(cls)) return@collector
                if (cls.name in excludingImplementations) return@collector
                val area = lightServiceArea(cls)
                val (pid, pname) = pluginIdAndName(pluginDescriptor, cls)
                out += ServiceInfo(
                    interfaceClass = null,
                    implementationClass = cls.name,
                    area = area,
                    preload = "FALSE",
                    providedByPluginId = pid,
                    providedByPluginName = pname,
                    source = "light_instantiated",
                )
            } catch (t: Throwable) {
                thisLogger().debug("Light-service collection failed for ${cls.name}", t)
            }
        }
        try {
            method.invoke(componentManager, callback)
        } catch (t: Throwable) {
            thisLogger().debug("processAllImplementationClasses invocation failed", t)
        }
    }

    private fun readContainer(descriptor: IdeaPluginDescriptor, getterName: String): Any? {
        val m = descriptor.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterCount == 0
        } ?: return null
        return try { m.invoke(descriptor) } catch (_: Throwable) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readServiceList(container: Any): List<ServiceDescriptor> {
        // ContainerDescriptor.services is a @JvmField, exposed as a public field on the JVM.
        val field = container.javaClass.fields.firstOrNull { it.name == "services" }
            ?: return emptyList()
        val raw = try { field.get(container) } catch (_: Throwable) { return emptyList() }
        val list = raw as? List<*> ?: return emptyList()
        return list.filterIsInstance<ServiceDescriptor>()
    }

    internal fun isLightService(cls: Class<*>): Boolean {
        // Mirrors com.intellij.serviceContainer.isLightService: final + @Service.
        return Modifier.isFinal(cls.modifiers) && cls.isAnnotationPresent(Service::class.java)
    }

    /** Reads `@Service(value = [..])` and maps the first level to "application" | "project". */
    internal fun lightServiceArea(cls: Class<*>): String {
        val annotation = cls.getAnnotation(Service::class.java) ?: return "application"
        val levels = annotation.value
        if (levels.isEmpty()) return "application"  // Service.Level default == APP
        return when (levels.first().name) {
            "PROJECT" -> "project"
            else -> "application"
        }
    }

    private fun pluginIdAndName(pluginDescriptor: Any?, cls: Class<*>): Pair<String, String?> {
        val direct = pluginDescriptor?.let { pd ->
            ExtensionPointInspector.extractPluginIdString(pd)?.let { id ->
                id to ExtensionPointInspector.readMethod(pd, "getName")?.toString()
            }
        }
        if (direct != null) return direct
        // Fall back: PluginAware classloader carries the descriptor on a `pluginDescriptor` field.
        val cl = cls.classLoader ?: return "unknown" to null
        val pdFromCl = ExtensionPointInspector.readField(cl, "pluginDescriptor")
            ?: ExtensionPointInspector.readMethod(cl, "getPluginDescriptor")
        if (pdFromCl != null) {
            val id = ExtensionPointInspector.extractPluginIdString(pdFromCl) ?: "unknown"
            val name = ExtensionPointInspector.readMethod(pdFromCl, "getName")?.toString()
            return id to name
        }
        return "unknown" to null
    }

    private val AREA_GETTERS = listOf(
        "application" to "getAppContainerDescriptor",
        "project" to "getProjectContainerDescriptor",
        "module" to "getModuleContainerDescriptor",
    )
}
