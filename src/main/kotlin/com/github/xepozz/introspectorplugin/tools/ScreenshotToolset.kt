package com.github.xepozz.introspectorplugin.tools

import com.github.xepozz.introspectorplugin.core.ComponentRegistry
import com.github.xepozz.introspectorplugin.core.ScreenshotCapture
import com.github.xepozz.introspectorplugin.util.encodePngBase64
import com.github.xepozz.introspectorplugin.util.onEdtBlocking
import com.github.xepozz.introspectorplugin.util.scaleImage
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.wm.WindowManager
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ImagePayload(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val base64: String,
    val warnings: List<String> = emptyList(),
)

class ScreenshotToolset : McpToolset {

    @McpTool(name = "screenshot.capture")
    @McpDescription(
        """Captures a screenshot of a component (target="component" + componentId), the active
IDE frame (target="active_frame"), every visible frame ("all_frames"), or the full virtual
desktop ("screen"). PNG, returned as base64. The image is auto-downscaled to stay within the
MCP response size budget; a warning is emitted when this happens."""
    )
    suspend fun screenshot_capture(
        @McpDescription("component | active_frame | all_frames | screen") target: String,
        @McpDescription("Required when target=component") componentId: String? = null,
        @McpDescription("png (only png supported in v1)") format: String = "png",
        @McpDescription("Post-render scale factor") scale: Double = 1.0,
    ): ImagePayload {
        val image: BufferedImage = when (target) {
            "component" -> {
                val id = componentId ?: throw mcpError("componentId is required for target=component")
                val c = ComponentRegistry.getInstance().lookup(id)
                    ?: throw mcpError("Component '$id' is no longer attached")
                onEdtBlocking { ScreenshotCapture.captureComponent(c) }
            }
            "active_frame" -> onEdtBlocking { ScreenshotCapture.captureActiveFrame() }
                ?: throw mcpError("No visible IDE frame")
            "all_frames" -> onEdtBlocking { captureAllFrames() } ?: throw mcpError("No visible IDE frames")
            "screen" -> captureScreenAll()
            else -> throw mcpError("Unknown target: $target")
        }
        return finalise(image, scale)
    }

    @McpTool(name = "screenshot.crop")
    @McpDescription(
        """Screenshots the active frame and returns a rectangular crop. coordinateSpace = "frame"
(relative to active frame's top-left) or "screen" (virtual desktop). Auto-fit to MCP budget."""
    )
    suspend fun screenshot_crop(
        @McpDescription("X coordinate") x: Int,
        @McpDescription("Y coordinate") y: Int,
        @McpDescription("Crop width") width: Int,
        @McpDescription("Crop height") height: Int,
        @McpDescription("frame | screen") coordinateSpace: String = "frame",
        @McpDescription("png (only png supported)") format: String = "png",
        @McpDescription("Post-render scale factor") scale: Double = 1.0,
    ): ImagePayload {
        val rect = Rectangle(x, y, width, height)
        val image = if (coordinateSpace == "frame") {
            val frame = onEdtBlocking { WindowManager.getInstance().findVisibleFrame() }
                ?: throw mcpError("No visible IDE frame")
            val full = onEdtBlocking { ScreenshotCapture.captureComponent(frame) }
            val clip = Rectangle(0, 0, full.width, full.height).intersection(rect)
            if (clip.isEmpty) throw mcpError("Empty crop after clipping to frame bounds")
            full.getSubimage(clip.x, clip.y, clip.width, clip.height)
        } else {
            ScreenshotCapture.captureRect(rect)
        }
        return finalise(image, scale)
    }

    private fun finalise(image: BufferedImage, scale: Double): ImagePayload {
        val scaled = scaleImage(image, scale)
        val (fitted, warning) = ScreenshotCapture.fitWithinBudget(scaled)
        return ImagePayload(
            mimeType = "image/png",
            width = fitted.width,
            height = fitted.height,
            base64 = encodePngBase64(fitted),
            warnings = listOfNotNull(warning),
        )
    }

    private fun captureAllFrames(): BufferedImage? {
        val frames = WindowManager.getInstance().allProjectFrames.mapNotNull { it.component }
        if (frames.isEmpty()) return null
        val height = frames.maxOf { it.height }
        val width = frames.sumOf { it.width }
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            var xCursor = 0
            for (frame in frames) {
                val img = ScreenshotCapture.captureComponent(frame)
                g.drawImage(img, xCursor, 0, null)
                xCursor += frame.width
            }
        } finally {
            g.dispose()
        }
        return out
    }

    private fun captureScreenAll(): BufferedImage {
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val bounds = ge.screenDevices.fold(Rectangle()) { acc, dev ->
            acc.union(dev.defaultConfiguration.bounds)
        }
        return ScreenshotCapture.captureRect(bounds)
    }

    private fun mcpError(message: String) = McpExpectedError(message, JsonObject(emptyMap()))
}
