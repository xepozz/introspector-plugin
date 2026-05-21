package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
