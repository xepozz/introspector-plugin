package com.github.xepozz.introspectorplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class ComponentProperty(
    val name: String,
    val value: String,
)
