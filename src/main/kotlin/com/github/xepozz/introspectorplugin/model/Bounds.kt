package com.github.xepozz.introspectorplugin.model

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
