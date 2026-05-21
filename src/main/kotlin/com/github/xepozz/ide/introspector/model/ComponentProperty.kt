package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

@Serializable
data class ComponentProperty(
    val name: String,
    val value: String,
)
