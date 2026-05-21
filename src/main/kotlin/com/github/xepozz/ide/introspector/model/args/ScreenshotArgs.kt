package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

@Serializable
data class CaptureScreenshotArgs(
    val target: String,                       // "component" | "active_frame" | "all_frames" | "screen"
    val componentId: String? = null,
    val format: String = "png",
    val scale: Double = 1.0,
)

@Serializable
data class CropScreenshotArgs(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val coordinateSpace: String = "frame",
    val format: String = "png",
    val scale: Double = 1.0,
)
