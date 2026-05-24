package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.util.MAX_IMAGE_BYTES
import com.github.xepozz.ide.introspector.util.scaleImage
import com.intellij.openapi.wm.WindowManager
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Renders a [Component] off-screen via Component.paint(Graphics) — pure rendering, no
 * popups/tooltips. Must be called on the EDT.
 */
object ScreenshotCapture {

    fun captureComponent(component: Component): BufferedImage {
        val w = kotlin.math.max(1, component.width)
        val h = kotlin.math.max(1, component.height)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = img.createGraphics()
        try {
            component.paint(g)
        } finally {
            g.dispose()
        }
        return img
    }

    /** Real screen capture via Robot — includes popups/tooltips/overlays. */
    fun captureRect(rect: Rectangle): BufferedImage {
        val robot = Robot()
        return robot.createScreenCapture(rect)
    }

    /** Captures the active project frame on EDT. */
    fun captureActiveFrame(): BufferedImage? {
        val frame = WindowManager.getInstance().findVisibleFrame() ?: return null
        return captureComponent(frame)
    }

    /** Auto-downscales [image] until its PNG size is within [MAX_IMAGE_BYTES]. */
    fun fitWithinBudget(image: BufferedImage): Pair<BufferedImage, String?> =
        fitWithinBudget(image, MAX_IMAGE_BYTES, maxAttempts = 4)

    /**
     * Test-visible overload: same algorithm as [fitWithinBudget] but parameterised so tests
     * can drive it with smaller budgets / attempt caps instead of producing many-MB images.
     */
    internal fun fitWithinBudget(
        image: BufferedImage,
        budgetBytes: Int,
        maxAttempts: Int,
    ): Pair<BufferedImage, String?> {
        var current = image
        var warning: String? = null
        var attempts = 0
        while (encodedSize(current) > budgetBytes && attempts < maxAttempts) {
            current = scaleImage(current, 0.5)
            attempts++
            warning = "Image was downscaled to fit MCP response size budget (${attempts} halving passes)."
        }
        return current to warning
    }

    internal fun encodedSize(image: BufferedImage): Int {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.size()
    }

    /**
     * Result of [drawHighlight]: the annotated copy of the base image, plus any warnings
     * raised while computing or drawing the overlay (e.g. clipped bounds, zero-area marker).
     */
    data class HighlightResult(val image: BufferedImage, val warnings: List<String>)

    /**
     * Returns a copy of [base] with a [color]-stroked rectangle of [thickness] source-pixels
     * drawn at [bounds]. Pure CPU — must NOT touch the EDT or any IDE service. Safe to call
     * off the EDT on the [BufferedImage] returned by [captureComponent] / [captureActiveFrame].
     *
     * Behaviour:
     *   - [bounds] is intersected with the image rectangle. If the intersection is empty
     *     (component sits entirely outside the captured area) a small marker is drawn at the
     *     image origin and a warning is returned.
     *   - If [bounds] is partially off-image the visible portion is drawn and a warning is
     *     returned.
     *   - Zero-area bounds (`width == 0 || height == 0`) also draw a marker at the bounds
     *     origin clamped to the image and emit a warning.
     *   - [label] (when non-blank) is drawn just above the box, or inside-top of the box
     *     when there is no room above. Newlines / carriage returns are collapsed to spaces.
     *
     * [thickness] is clamped to `1..20` (callers should clamp first; this is a defensive
     * second line of defence). Drawing uses anti-aliasing; tests sample box-interior pixels
     * rather than stroke edges to stay robust to AA fuzz.
     */
    fun drawHighlight(
        base: BufferedImage,
        bounds: Rectangle,
        color: Color,
        thickness: Int,
        label: String? = null,
    ): HighlightResult {
        val warnings = mutableListOf<String>()
        // Ensure a writable ARGB copy — paint() output is already TYPE_INT_ARGB but some
        // callers (e.g. Robot screen captures) may hand us TYPE_INT_RGB / TYPE_3BYTE_BGR.
        val out = if (base.type == BufferedImage.TYPE_INT_ARGB) {
            val copy = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
            val cg = copy.createGraphics()
            try { cg.drawImage(base, 0, 0, null) } finally { cg.dispose() }
            copy
        } else {
            val copy = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
            val cg = copy.createGraphics()
            try { cg.drawImage(base, 0, 0, null) } finally { cg.dispose() }
            copy
        }
        val clampedThickness = thickness.coerceIn(1, 20)
        val imageRect = Rectangle(0, 0, out.width, out.height)
        val zeroArea = bounds.width <= 0 || bounds.height <= 0
        val intersected: Rectangle = if (zeroArea) Rectangle() else imageRect.intersection(bounds)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = color
            g.stroke = BasicStroke(clampedThickness.toFloat())
            when {
                zeroArea -> {
                    warnings += "component has zero-area bounds; drew marker only"
                    val mx = bounds.x.coerceIn(0, kotlin.math.max(0, out.width - 1))
                    val my = bounds.y.coerceIn(0, kotlin.math.max(0, out.height - 1))
                    val size = kotlin.math.max(4, clampedThickness * 2)
                    g.fillRect(mx, my, size, size)
                }
                intersected.isEmpty -> {
                    warnings += "component lies outside captured frame; drew marker only"
                    val size = kotlin.math.max(4, clampedThickness * 2)
                    g.fillRect(0, 0, size, size)
                }
                else -> {
                    if (intersected != bounds) {
                        warnings += "component clipped to frame bounds"
                    }
                    // Inset by half-stroke so the stroke stays inside the box bounds for thick
                    // strokes; clamp draw rect to keep us inside the image.
                    val half = clampedThickness / 2
                    val drawX = (intersected.x + half).coerceAtMost(out.width - 1)
                    val drawY = (intersected.y + half).coerceAtMost(out.height - 1)
                    val drawW = (intersected.width - clampedThickness).coerceAtLeast(1)
                    val drawH = (intersected.height - clampedThickness).coerceAtLeast(1)
                    g.drawRect(drawX, drawY, drawW, drawH)

                    val cleanedLabel = label?.replace(Regex("[\r\n]+"), " ")?.trim().orEmpty()
                    if (cleanedLabel.isNotEmpty()) {
                        g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
                        val fm = g.fontMetrics
                        val textWidth = fm.stringWidth(cleanedLabel)
                        val textHeight = fm.height
                        val padding = 3
                        val labelAbove = intersected.y >= textHeight + padding
                        val baselineY = if (labelAbove) intersected.y - padding else
                            (intersected.y + fm.ascent + padding).coerceAtMost(out.height - padding)
                        val baselineX = intersected.x.coerceAtMost((out.width - textWidth - padding).coerceAtLeast(0))
                        // Box background behind the text so it stays legible against any UI.
                        val bgX = baselineX - padding
                        val bgY = baselineY - fm.ascent
                        val bgW = textWidth + 2 * padding
                        val bgH = textHeight
                        val prevColor = g.color
                        g.color = Color(0, 0, 0, 160)
                        g.fillRect(bgX, bgY, bgW, bgH)
                        g.color = prevColor
                        g.drawString(cleanedLabel, baselineX, baselineY)
                    }
                }
            }
        } finally {
            g.dispose()
        }
        return HighlightResult(out, warnings)
    }
}
