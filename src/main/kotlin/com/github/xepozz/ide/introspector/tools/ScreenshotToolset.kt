package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.ComponentRegistry
import com.github.xepozz.ide.introspector.core.ImageDiffer
import com.github.xepozz.ide.introspector.core.ScreenshotCapture
import com.github.xepozz.ide.introspector.model.Bounds
import com.github.xepozz.ide.introspector.model.ImageDiffPayload
import com.github.xepozz.ide.introspector.util.encodePngBase64
import com.github.xepozz.ide.introspector.util.onEdtBlocking
import com.github.xepozz.ide.introspector.util.parseCssColor
import com.github.xepozz.ide.introspector.util.scaleImage
import com.github.xepozz.ide.introspector.util.truncateUtf8
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.wm.WindowManager
import java.awt.Color
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
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

    @McpTool(name = "screenshot.highlight")
    @McpDescription(
        """
        |Captures a screenshot of the IDE (target='component'|'active_frame'|'screen') AND
        |overlays a colored bounding rectangle around the Swing component identified by
        |componentId, optionally with a text label. Returns the same base64-PNG
        |ImagePayload as screenshot.capture, downscaled to the MCP response budget.
        |
        |target options (must match the coordinate space the highlight is drawn in):
        |  - "component"    — render only the target component; the box fills it. Cheapest.
        |  - "active_frame" — render the focused IDE frame, box at frame-relative bounds
        |                     (Component.getLocationOnScreen minus frame origin). Off-screen
        |                     components are clipped to the frame edge with a warning.
        |  - "screen"       — Robot capture of the virtual desktop with the box at the
        |                     component's absolute screen coordinates. The only target that
        |                     includes popups / tooltips / floating overlays.
        |
        |Use this when: the user asks "where is X on screen?", or after a ui.find_by_*
        |call you want to visually confirm which component matched, or to annotate a
        |screenshot for screenshot-and-narrate workflows.
        |
        |Do NOT use this when: you just want raw pixels (use screenshot.capture), you want
        |a crop centered on the component (use screenshot.crop after ui.get_properties), or
        |you need to highlight multiple components in one image (not supported in v1).
        |
        |Returns: { mimeType:"image/png", width:int, height:int, base64:string,
        |warnings:string[] } — same shape as screenshot.capture. warnings includes
        |"component clipped to frame", "color parse fell back to red", and the standard
        |"image downscaled to fit budget" notices.
        |
        |Examples:
        |  componentId="c_a3f2e1b8"                                    — red box on active frame
        |  componentId="c_a3f2e1b8", color="#33CC33", thickness=5      — fatter green box
        |  componentId="c_a3f2e1b8", target="screen", label="OK btn"   — labelled, includes popups
        |  componentId="c_a3f2e1b8", target="component", scale=0.5     — component-only, halved
        """
    )
    suspend fun screenshot_highlight(
        @McpDescription("ComponentRegistry id (from ui.find_by_* / ui.get_tree). Must still be attached.")
        componentId: String,
        @McpDescription("'component' | 'active_frame' | 'screen' (matches screenshot.capture, minus 'all_frames').")
        target: String = "active_frame",
        @McpDescription("CSS hex (#RRGGBB / #RGB / #RRGGBBAA) or named color. Invalid → red + warning.")
        color: String = "#FF0000",
        @McpDescription("Source-pixel stroke thickness. Clamped to 1..20.")
        thickness: Int = 3,
        @McpDescription("Optional text label drawn above (or inside-top) of the box. Truncated to 80 UTF-8 bytes; \\n/\\r collapsed.")
        label: String? = null,
        @McpDescription("Post-render scale factor applied AFTER overlay so the stroke scales with the image.")
        scale: Double = 1.0,
        @McpDescription("Image format. Only 'png' supported in v1.")
        format: String = "png",
    ): ImagePayload {
        if (target == "all_frames") {
            throw mcpError("target='all_frames' is not supported by screenshot.highlight; use 'active_frame' or 'screen'")
        }
        val component = ComponentRegistry.getInstance().lookup(componentId)
            ?: throw mcpError("Component '$componentId' is no longer attached")

        val warnings = mutableListOf<String>()
        val parsedColor = parseCssColor(color) ?: run {
            warnings += "color '$color' could not be parsed; falling back to red"
            Color.RED
        }
        val clampedThickness = thickness.coerceIn(1, 20)
        val cleanLabel = label?.let {
            val collapsed = it.replace(Regex("[\r\n]+"), " ")
            val t = truncateUtf8(collapsed, 80)
            if (t.truncated) warnings += "label truncated to 80 UTF-8 bytes"
            t.text
        }

        // EDT critical section: capture base image + read on-screen coords. Overlay draw
        // happens AFTER this returns, off the EDT.
        data class BasePaint(val image: BufferedImage, val bounds: Rectangle)
        val basePaint: BasePaint = onEdtBlocking {
            when (target) {
                "component" -> {
                    val img = ScreenshotCapture.captureComponent(component)
                    BasePaint(img, Rectangle(0, 0, img.width, img.height))
                }
                "active_frame" -> {
                    val frame = WindowManager.getInstance().findVisibleFrame()
                        ?: throw mcpError("No visible IDE frame")
                    val frameImg = ScreenshotCapture.captureComponent(frame)
                    val rect = relativeBounds(component, frame)
                    BasePaint(frameImg, rect)
                }
                "screen" -> {
                    val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    val virtualBounds = ge.screenDevices.fold(Rectangle()) { acc, dev ->
                        acc.union(dev.defaultConfiguration.bounds)
                    }
                    val screenImg = ScreenshotCapture.captureRect(virtualBounds)
                    val onScreen = absoluteBoundsOrNull(component)
                        ?: throw mcpError("Component is not currently visible — cannot compute screen coords")
                    val rect = Rectangle(
                        onScreen.x - virtualBounds.x,
                        onScreen.y - virtualBounds.y,
                        onScreen.width,
                        onScreen.height,
                    )
                    BasePaint(screenImg, rect)
                }
                else -> throw mcpError("Unknown target: $target")
            }
        }

        val highlight = ScreenshotCapture.drawHighlight(
            base = basePaint.image,
            bounds = basePaint.bounds,
            color = parsedColor,
            thickness = clampedThickness,
            label = cleanLabel,
        )
        warnings += highlight.warnings
        return finalise(highlight.image, scale, extraWarnings = warnings)
    }

    @McpTool(name = "screenshot.diff")
    @McpDescription(
        """
        |Pixel-diff two base64-encoded PNGs (typically a 'before' and 'after' from two
        |prior screenshot.capture calls) and return a composite image highlighting changed
        |pixels plus structured diff stats. Pure CPU — no EDT, no IDE state touched.
        |
        |The output is a desaturated, dimmed version of 'after' with differing pixels
        |tinted in highlightColor. A bbox is computed for the smallest axis-aligned
        |rectangle containing every differing pixel (null when no pixels differ).
        |
        |Use this when: an agent needs to verify a UI change had a visible effect ("did
        |Toggle Sidebar actually toggle it?"), localize the changed region of a screen
        |before zooming in, or compute a quick "% changed" sanity number before spending
        |tokens on a vision pass.
        |
        |Do NOT use this when: you want a fresh screenshot (use screenshot.capture), you
        |want to highlight a known component (use screenshot.highlight — no diff needed),
        |or you want OCR / semantic comparison (this is pixel math, not vision).
        |
        |tolerance is per-channel (R/G/B/A independently). 0 = exact match required; 8
        |(default) absorbs subpixel-rendering jitter and JBR HiDPI antialiasing noise.
        |sizeMismatchPolicy: 'resize' (default, safe for HiDPI/window-resize), 'pad' (top-
        |left align, faithful but inflates 'changed' regions), 'error' (reject).
        |
        |Returns: { mimeType:"image/png", width, height, base64, warnings:string[],
        |totalPixels:int, differingPixels:int, diffPercentage:double,
        |bbox:{x,y,width,height}? }.
        |
        |Examples:
        |  before=<b64>, after=<b64>                                    — defaults
        |  before=<b64>, after=<b64>, tolerance=0                       — exact match
        |  before=<b64>, after=<b64>, sizeMismatchPolicy="error"        — strict sizes
        |  before=<b64>, after=<b64>, highlightColor="#FFFF00",
        |    baseTransparency=0.2f                                      — yellow on dark
        """
    )
    suspend fun screenshot_diff(
        @McpDescription("Base64-encoded PNG of the 'before' state (typically a prior screenshot.capture.base64).")
        before: String,
        @McpDescription("Base64-encoded PNG of the 'after' state.")
        after: String,
        @McpDescription("Per-channel tolerance (R/G/B/A). 0 = exact, 8 (default) masks AA jitter. Clamped 0..255.")
        tolerance: Int = 8,
        @McpDescription("CSS hex / named color for the highlight tint over changed pixels. Invalid → red + warning.")
        highlightColor: String = "#FF0000",
        @McpDescription("Opacity of the desaturated 'after' base behind the highlight (0.0..1.0). Clamped.")
        baseTransparency: Float = 0.4f,
        @McpDescription("'resize' (default; bilinear, HiDPI/window-resize safe), 'pad' (top-left), 'error' (reject).")
        sizeMismatchPolicy: String = "resize",
    ): ImageDiffPayload {
        val warnings = mutableListOf<String>()
        val beforeImg = decodePng(before, "before") ?: throw mcpError("'before' is not a valid PNG")
        val afterImg = decodePng(after, "after") ?: throw mcpError("'after' is not a valid PNG")
        val parsedColor = parseCssColor(highlightColor) ?: run {
            warnings += "highlightColor '$highlightColor' could not be parsed; falling back to red"
            Color.RED
        }
        val policy = when (sizeMismatchPolicy.lowercase()) {
            "resize" -> ImageDiffer.SizeMismatchPolicy.Resize
            "pad" -> ImageDiffer.SizeMismatchPolicy.Pad
            "error" -> ImageDiffer.SizeMismatchPolicy.Error
            else -> throw mcpError("Unknown sizeMismatchPolicy: '$sizeMismatchPolicy' (expected 'resize'|'pad'|'error')")
        }

        val diff = try {
            ImageDiffer.diff(
                before = beforeImg,
                after = afterImg,
                tolerance = tolerance,
                highlightColor = parsedColor,
                baseAlpha = baseTransparency,
                sizeMismatchPolicy = policy,
            )
        } catch (e: ImageDiffer.SizeMismatchException) {
            throw mcpError(e.message ?: "size mismatch")
        }
        warnings += diff.warnings

        // Fit to MCP response budget — same downscale path as the rest of the toolset. The
        // bbox is reported in the (possibly scaled) output coordinates; emit a warning when
        // a scale happens so callers know.
        val (fitted, budgetWarning) = ScreenshotCapture.fitWithinBudget(diff.composite)
        if (budgetWarning != null) warnings += budgetWarning
        val scaledBbox = if (diff.bbox == null || fitted === diff.composite) diff.bbox else {
            warnings += "output downscaled to fit budget; bbox is in scaled coordinates"
            val sx = fitted.width.toDouble() / diff.composite.width.toDouble()
            val sy = fitted.height.toDouble() / diff.composite.height.toDouble()
            Bounds(
                x = (diff.bbox.x * sx).toInt().coerceIn(0, kotlin.math.max(0, fitted.width - 1)),
                y = (diff.bbox.y * sy).toInt().coerceIn(0, kotlin.math.max(0, fitted.height - 1)),
                width = kotlin.math.max(1, (diff.bbox.width * sx).toInt()).coerceAtMost(fitted.width),
                height = kotlin.math.max(1, (diff.bbox.height * sy).toInt()).coerceAtMost(fitted.height),
            )
        }
        return ImageDiffPayload(
            mimeType = "image/png",
            width = fitted.width,
            height = fitted.height,
            base64 = encodePngBase64(fitted),
            warnings = warnings,
            totalPixels = diff.totalPixels,
            differingPixels = diff.differingPixels,
            diffPercentage = diff.diffPercentage,
            bbox = scaledBbox,
        )
    }

    /** Computes [component]'s bounds relative to [frame]'s top-left. EDT-only. */
    private fun relativeBounds(component: Component, frame: Component): Rectangle {
        val compScreen: Point = absoluteOriginOrNull(component)
            ?: throw mcpError("Component is not currently visible — cannot compute frame coords")
        val frameScreen: Point = absoluteOriginOrNull(frame)
            ?: throw mcpError("Active IDE frame is not on screen — cannot compute frame coords")
        return Rectangle(
            compScreen.x - frameScreen.x,
            compScreen.y - frameScreen.y,
            kotlin.math.max(0, component.width),
            kotlin.math.max(0, component.height),
        )
    }

    private fun absoluteBoundsOrNull(component: Component): Rectangle? {
        val origin = absoluteOriginOrNull(component) ?: return null
        return Rectangle(origin.x, origin.y, component.width, component.height)
    }

    private fun absoluteOriginOrNull(component: Component): Point? =
        runCatching { component.locationOnScreen }.getOrNull()

    private fun decodePng(base64: String, label: String): BufferedImage? {
        val bytes = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull()
            ?: return null
        return runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?.also { /* label kept for future logging */ }
    }

    private fun finalise(
        image: BufferedImage,
        scale: Double,
        extraWarnings: List<String> = emptyList(),
    ): ImagePayload {
        val scaled = scaleImage(image, scale)
        val (fitted, warning) = ScreenshotCapture.fitWithinBudget(scaled)
        return ImagePayload(
            mimeType = "image/png",
            width = fitted.width,
            height = fitted.height,
            base64 = encodePngBase64(fitted),
            warnings = extraWarnings + listOfNotNull(warning),
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
