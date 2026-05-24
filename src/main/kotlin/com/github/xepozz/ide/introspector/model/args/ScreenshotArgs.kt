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

@Serializable
data class HighlightScreenshotArgs(
    val componentId: String,
    val target: String = "active_frame",        // 'component' | 'active_frame' | 'screen'
    val color: String = "#FF0000",
    val thickness: Int = 3,                     // clamped 1..20
    val label: String? = null,                  // truncated to 80 UTF-8 bytes, \n / \r collapsed
    val scale: Double = 1.0,
    val format: String = "png",
)

@Serializable
data class DiffScreenshotArgs(
    val before: String,                         // base64 PNG
    val after: String,                          // base64 PNG
    val tolerance: Int = 8,                     // per-channel, clamped 0..255
    val highlightColor: String = "#FF0000",
    val baseTransparency: Float = 0.4f,         // clamped 0.0..1.0
    val sizeMismatchPolicy: String = "resize",  // 'resize' | 'pad' | 'error'
)
