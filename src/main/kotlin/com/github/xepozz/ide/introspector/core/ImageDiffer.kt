package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.Bounds
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Pure-CPU pixel diff for [BufferedImage]s — no EDT, no IntelliJ APIs.
 *
 * Used by the `screenshot.diff` MCP tool to produce a composite that highlights changed
 * pixels plus stats (totalPixels, differingPixels, percentage, bounding box). The diff
 * algorithm is per-channel ARGB with a tolerance per channel — `tolerance == 0` means an
 * exact match, the default `8` masks subpixel-AA jitter and JBR HiDPI noise.
 */
object ImageDiffer {

    /**
     * Result of [diff]:
     *  - [composite] is the dimmed grayscale `after` with changed pixels tinted by the
     *    requested highlight colour.
     *  - [totalPixels] / [differingPixels] reflect the dimensions actually compared (after
     *    any resize/pad policy was applied) — NOT the original input sizes.
     *  - [bbox] is the smallest axis-aligned rectangle covering every differing pixel, or
     *    `null` when no pixels differ.
     */
    data class DiffResult(
        val composite: BufferedImage,
        val totalPixels: Int,
        val differingPixels: Int,
        val diffPercentage: Double,    // 0.0..100.0, rounded to 4 decimal places
        val bbox: Bounds?,
        val warnings: List<String>,
    )

    /**
     * Size-mismatch policy:
     *   - [Resize] — bilinear-resize `before` to `after`'s dimensions before diffing.
     *   - [Pad]    — top-left align the smaller into the larger; out-of-bounds counts as
     *                "differing" (transparent-vs-opaque is always a diff at any tolerance).
     *   - [Error]  — refuse to diff; caller turns this into an MCP error.
     */
    enum class SizeMismatchPolicy { Resize, Pad, Error }

    class SizeMismatchException(message: String) : RuntimeException(message)

    /**
     * Returns a [DiffResult] for [before] vs [after].
     *
     * [tolerance] is clamped to `0..255` and applied per channel (R/G/B/A) independently —
     * the pixel is "different" if ANY channel exceeds the threshold.
     *
     * [baseAlpha] is clamped to `0f..1f`; the composite shows the desaturated `after` at
     * that opacity behind the highlight tint, so callers can dim the unchanged area without
     * losing spatial context entirely.
     */
    fun diff(
        before: BufferedImage,
        after: BufferedImage,
        tolerance: Int = 8,
        highlightColor: Color = Color.RED,
        baseAlpha: Float = 0.4f,
        sizeMismatchPolicy: SizeMismatchPolicy = SizeMismatchPolicy.Resize,
    ): DiffResult {
        val tol = tolerance.coerceIn(0, 255)
        val alpha = baseAlpha.coerceIn(0f, 1f)
        val warnings = mutableListOf<String>()

        val (b, a) = align(before, after, sizeMismatchPolicy, warnings)
        val width = a.width
        val height = a.height
        val totalPixels = width * height

        // Build the composite background: desaturated, alpha-blended copy of `after`.
        val composite = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        run {
            val g: Graphics2D = composite.createGraphics()
            try {
                // Fill transparent then draw desaturated `after` at the requested alpha.
                g.composite = AlphaComposite.Clear
                g.fillRect(0, 0, width, height)
                g.composite = AlphaComposite.SrcOver
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.drawImage(desaturate(a), 0, 0, null)
                // Re-apply alpha by re-drawing transparent black on top, except we want the
                // *base* pixels themselves dimmed — handled below by per-pixel write.
            } finally {
                g.dispose()
            }
            // Apply the alpha factor to every base pixel — preserves desaturated colour but
            // reduces opacity so the highlight tint stands out.
            if (alpha < 1f) {
                val pixels = (composite.raster.dataBuffer as java.awt.image.DataBufferInt).data
                val factor = (alpha * 255f).toInt().coerceIn(0, 255)
                for (i in pixels.indices) {
                    val argb = pixels[i]
                    val oldA = (argb ushr 24) and 0xFF
                    val newA = (oldA * factor) / 255
                    pixels[i] = (newA shl 24) or (argb and 0x00FFFFFF)
                }
            }
        }

        // Per-pixel diff + highlight write.
        val bPixels = pixelsArgb(b)
        val aPixels = pixelsArgb(a)
        val outPixels = (composite.raster.dataBuffer as java.awt.image.DataBufferInt).data
        val tint = (0xFF shl 24) or
            ((highlightColor.red and 0xFF) shl 16) or
            ((highlightColor.green and 0xFF) shl 8) or
            (highlightColor.blue and 0xFF)

        var differingPixels = 0
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE

        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                val idx = rowBase + x
                val bp = bPixels[idx]
                val ap = aPixels[idx]
                if (pixelDiffers(bp, ap, tol)) {
                    differingPixels++
                    outPixels[idx] = tint
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }

        val percentage = if (totalPixels == 0) 0.0 else
            ((differingPixels.toDouble() * 100.0) / totalPixels).round4()
        val bbox = if (differingPixels == 0) null else
            Bounds(x = minX, y = minY, width = maxX - minX + 1, height = maxY - minY + 1)

        return DiffResult(
            composite = composite,
            totalPixels = totalPixels,
            differingPixels = differingPixels,
            diffPercentage = percentage,
            bbox = bbox,
            warnings = warnings,
        )
    }

    // ====================================================================================
    // Internals
    // ====================================================================================

    private fun align(
        before: BufferedImage,
        after: BufferedImage,
        policy: SizeMismatchPolicy,
        warnings: MutableList<String>,
    ): Pair<BufferedImage, BufferedImage> {
        val asArgbAfter = ensureArgb(after)
        if (before.width == after.width && before.height == after.height) {
            return ensureArgb(before) to asArgbAfter
        }
        return when (policy) {
            SizeMismatchPolicy.Error ->
                throw SizeMismatchException(
                    "Image dimensions differ (before=${before.width}x${before.height}, " +
                        "after=${after.width}x${after.height}) and sizeMismatchPolicy='error'"
                )
            SizeMismatchPolicy.Resize -> {
                warnings += "before resized from ${before.width}x${before.height} to " +
                    "${after.width}x${after.height} to match after"
                resize(before, after.width, after.height) to asArgbAfter
            }
            SizeMismatchPolicy.Pad -> {
                val w = kotlin.math.max(before.width, after.width)
                val h = kotlin.math.max(before.height, after.height)
                warnings += "before/after padded to ${w}x${h} (top-left aligned)"
                pad(before, w, h) to pad(after, w, h)
            }
        }
    }

    private fun ensureArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try { g.drawImage(src, 0, 0, null) } finally { g.dispose() }
        return out
    }

    private fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, w, h, null)
        } finally { g.dispose() }
        return out
    }

    private fun pad(src: BufferedImage, w: Int, h: Int): BufferedImage {
        if (src.width == w && src.height == h && src.type == BufferedImage.TYPE_INT_ARGB) return src
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            // Leaves the OOB region as transparent (alpha=0). For the diff, transparent vs
            // opaque is a per-channel difference even at tolerance > 0 — which is the
            // intended "pad" semantic: the missing area counts as changed.
            g.drawImage(src, 0, 0, null)
        } finally { g.dispose() }
        return out
    }

    private fun pixelsArgb(img: BufferedImage): IntArray {
        // Fastest path when the underlying raster IS the int[] we want.
        if (img.type == BufferedImage.TYPE_INT_ARGB) {
            val buf = img.raster.dataBuffer
            if (buf is java.awt.image.DataBufferInt) return buf.data
        }
        val arr = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, arr, 0, img.width)
        return arr
    }

    private fun pixelDiffers(p1: Int, p2: Int, tol: Int): Boolean {
        if (p1 == p2) return false
        val a1 = (p1 ushr 24) and 0xFF; val a2 = (p2 ushr 24) and 0xFF
        if (kotlin.math.abs(a1 - a2) > tol) return true
        val r1 = (p1 ushr 16) and 0xFF; val r2 = (p2 ushr 16) and 0xFF
        if (kotlin.math.abs(r1 - r2) > tol) return true
        val g1 = (p1 ushr 8) and 0xFF;  val g2 = (p2 ushr 8) and 0xFF
        if (kotlin.math.abs(g1 - g2) > tol) return true
        val b1 = p1 and 0xFF;            val b2 = p2 and 0xFF
        if (kotlin.math.abs(b1 - b2) > tol) return true
        return false
    }

    /** Returns a desaturated (grayscale) ARGB copy of [src]. Keeps original alpha. */
    private fun desaturate(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val srcPixels = pixelsArgb(src)
        val outPixels = (out.raster.dataBuffer as java.awt.image.DataBufferInt).data
        for (i in srcPixels.indices) {
            val argb = srcPixels[i]
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            // Rec. 601 luma — cheap, good enough for visual diff backgrounds.
            val y = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            outPixels[i] = (a shl 24) or (y shl 16) or (y shl 8) or y
        }
        return out
    }

    private fun Double.round4(): Double = kotlin.math.round(this * 10000.0) / 10000.0
}
