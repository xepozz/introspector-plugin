package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * One IDE service. Mirrors `<applicationService>` / `<projectService>` / `<moduleService>` from a
 * plugin.xml, plus `@Service`-annotated light services discovered as already-instantiated.
 */
@Serializable
data class ServiceInfo(
    /** Raw `serviceInterface` attribute, or null when interface == implementation. */
    val interfaceClass: String?,
    /** Canonical implementation FQCN — the value the IDE will instantiate in the normal env. */
    val implementationClass: String,
    val testServiceImplementation: String? = null,
    val headlessImplementation: String? = null,
    /** "application" | "project" | "module" */
    val area: String,
    /** ServiceDescriptor.PreloadMode name: "FALSE" | "TRUE" | "AWAIT" | "NOT_HEADLESS" | "NOT_LIGHT_EDIT". */
    val preload: String = "FALSE",
    /** ClientKind name when set (e.g. "ALL", "LOCAL", "OWNER"). */
    val client: String? = null,
    /** ExtensionDescriptor.Os name when restricted. */
    val os: String? = null,
    val overrides: Boolean = false,
    val configurationSchemaKey: String? = null,
    val providedByPluginId: String,
    val providedByPluginName: String?,
    /** "xml" for plugin.xml-declared, "light_instantiated" for already-created @Service classes. */
    val source: String = "xml",
)

@Serializable
data class ListServicesResponse(
    val services: List<ServiceInfo>,
    val total: Int,
)
