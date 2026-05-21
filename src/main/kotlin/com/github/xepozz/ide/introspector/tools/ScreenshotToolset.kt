package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.ComponentRegistry
import com.github.xepozz.ide.introspector.core.ScreenshotCapture
import com.github.xepozz.ide.introspector.util.encodePngBase64
import com.github.xepozz.ide.introspector.util.onEdtBlocking
import com.github.xepozz.ide.introspector.util.scaleImage
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
        """
        |Captures a PNG screenshot of part or all of the IDE and returns it as a base64-encoded
        |string inside the response payload. The image is auto-downscaled (halving passes) until
        |the PNG fits the MCP response budget (~1.5 MB); a warning is emitted when this happens.
        |
        |target options:
        |  - "component"    — render one specific component via its off-screen paint(). Requires
        |                     componentId from a prior ui.find_by_* / ui.get_tree call. Does NOT
        |                     include popups / tooltips / floating overlays.
        |  - "active_frame" — render the currently focused IDE frame (same caveat re overlays).
        |  - "all_frames"   — render every open project frame, tiled horizontally.
        |  - "screen"       — use java.awt.Robot to capture the full virtual desktop. This is the
        |                     only target that includes popups, tooltips and other floating UI.
        |
        |Use this when: the user asks for a screenshot, or you need to visually verify a
        |UI state you suspect from a ui.* call.
        |
        |Do NOT use this when: a textual UI dump (ui.get_tree / ui.get_properties) would
        |answer the question — text is cheaper and easier to reason about.
        |
        |Returns: { mimeType:"image/png", width:int, height:int, base64:string, warnings:string[] }.
        |
        |Examples:
        |  target="active_frame"                                 — the current IDE window
        |  target="component", componentId="c_a3f2e1b8"          — one button or panel
        |  target="screen", scale=0.5                            — full desktop, halved for size
        """
    )
    suspend fun screenshot_capture(
        @McpDescription("'component' | 'active_frame' | 'all_frames' | 'screen'. See tool description for differences (only 'screen' includes popups).")
        target: String,
        @McpDescription("Required when target='component'. Stable id from a prior ui.find_by_* or ui.get_tree call.")
        componentId: String? = null,
        @McpDescription("Image format. Only 'png' supported in v1.")
        format: String = "png",
        @McpDescription("Post-render scale factor applied before encoding. Use 0.5 or 0.25 to halve/quarter for token budget.")
        scale: Double = 1.0,
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
        """
        |Returns a rectangular crop of either the active IDE frame or the virtual desktop.
        |Cheaper than screenshot.capture+downscale when you only need a small region (e.g.
        |"the toolbar at the top" or "this dialog's OK button area").
        |
        |Use this when: you want a focused crop of a known region (after locating the
        |interesting bounds via ui.find_by_* / ui.get_tree).
        |
        |Do NOT use this when: you need the whole IDE (use screenshot.capture
        |target='active_frame'), or you need overlays/popups not painted by the active
        |frame (use screenshot.capture target='screen').
        |
        |coordinateSpace:
        |  - "frame"  (default) — (x,y) measured from active IDE frame's top-left corner.
        |                         Crop is clipped to the frame; out-of-bounds → error.
        |  - "screen"           — (x,y) on the virtual desktop (multi-monitor safe).
        |
        |Returns: same shape as screenshot.capture — { mimeType:"image/png", width, height,
        |base64, warnings:string[] }.
        |
        |Examples:
        |  x=0, y=0, width=1200, height=60                  — top toolbar of the active frame
        |  x=100, y=200, width=400, height=300, coordinateSpace="screen" — desktop region
        """
    )
    suspend fun screenshot_crop(
        @McpDescription("Left edge of crop in pixels (relative to frame or screen per coordinateSpace).")
        x: Int,
        @McpDescription("Top edge of crop in pixels.")
        y: Int,
        @McpDescription("Crop width in pixels. The intersection with the frame/screen bounds is used.")
        width: Int,
        @McpDescription("Crop height in pixels.")
        height: Int,
        @McpDescription("'frame' (default, relative to active IDE frame) or 'screen' (virtual desktop).")
        coordinateSpace: String = "frame",
        @McpDescription("Image format. Only 'png' supported in v1.")
        format: String = "png",
        @McpDescription("Post-render scale factor applied before encoding.")
        scale: Double = 1.0,
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
