package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * One IntelliJ message-bus listener declared statically via `<applicationListeners>` or
 * `<projectListeners>` in plugin.xml. Wired up by the platform when the corresponding
 * ComponentManager is initialized.
 */
@Serializable
data class ListenerInfo(
    /** FQCN of the Topic the listener subscribes to. */
    val topicClass: String,
    /** FQCN of the listener implementation. */
    val listenerClass: String,
    /** "application" | "project" */
    val area: String,
    val activeInTestMode: Boolean = true,
    val activeInHeadlessMode: Boolean = true,
    /** ExtensionDescriptor.Os name when restricted. */
    val os: String? = null,
    val providedByPluginId: String,
    val providedByPluginName: String?,
)

@Serializable
data class ListListenersResponse(
    val listeners: List<ListenerInfo>,
    val total: Int,
)
