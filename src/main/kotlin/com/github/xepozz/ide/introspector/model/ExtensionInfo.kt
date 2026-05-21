package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

@Serializable
data class ExtensionInfo(
    val extensionPointName: String,
    /** Bean class for BEAN_CLASS EPs; concrete implementation class for INTERFACE EPs. */
    val implementationClass: String?,
    /**
     * The user-supplied class actually contributed by the plugin: for INTERFACE EPs this equals
     * [implementationClass]; for BEAN_CLASS EPs this is read from an XML attribute on the bean
     * (`implementation` / `factoryClass` / `instance` / `serviceImplementation` / `serviceInterface`
     * / `class`).
     */
    val effectiveClass: String? = null,
    val providedByPluginId: String,
    val providedByPluginName: String?,
    val additionalAttributes: Map<String, String> = emptyMap(),
)

@Serializable
data class ListExtensionsResponse(
    val extensions: List<ExtensionInfo>,
    val total: Int,
)
