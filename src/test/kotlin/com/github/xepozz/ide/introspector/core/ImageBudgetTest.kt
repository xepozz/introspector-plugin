package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.max

/**
 * Tests for the internal helpers [ScreenshotCapture.fitWithinBudget] and
 * [ScreenshotCapture.encodedSize].
 *
 * `fitWithinBudget(image, budgetBytes, maxAttempts)` is the test-visible overload of the
 * production helper. Algorithm:
 *
 *   1. PNG-encode the image; if `<= budgetBytes`, return as-is with `warning == null`.
 *   2. Otherwise halve dimensions via `scaleImage(_, 0.5)`, increment attempt counter.
 *   3. Repeat until under budget or `attempts == maxAttempts`.
 *   4. Warning string is `"… (${attempts} halving passes)."` — verify counter matches.
 *
 * ## Fixture choices
 *
 *   * Solid-colour PNGs compress to a handful of bytes — perfect when you want "huge
 *     budget, never trigger downscale".
 *   * Gradients and pseudo-random noise compress poorly — useful for "force exactly N
 *     halving passes". Noise is seeded with a fixed `java.util.Random(seed)` so PNG size
 *     is deterministic across runs.
 *   * All images are `TYPE_INT_ARGB` to match `ScreenshotCapture.captureComponent`.
 */
class ImageBudgetTest {

    // ====================================================================================
    // SECTION 1. Image builders
    // ====================================================================================

    private fun solid(w: Int, h: Int, color: Color = Color.RED): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, w, h)
        } finally {
            g.dispose()
        }
        return img
    }

    /** Pseudo-random noise — PNG can barely compress this. Deterministic via [seed]. */
    private fun noise(w: Int, h: Int, seed: Long = 0xC0FFEEL): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val rng = Random(seed)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = rng.nextInt()
                img.setRGB(x, y, argb)
            }
        }
        return img
    }

    // ====================================================================================
    // SECTION 2. encodedSize
    // ====================================================================================

    @Test
    fun `encodedSize returns matching byte length of png`() {
        val img = solid(40, 30)
        val size = ScreenshotCapture.encodedSize(img)
        // Sanity bounds — a 40x30 PNG header alone is tens of bytes; solid colour body
        // adds a few more. Should be a small positive number.
        assertTrue("Expected positive PNG size, got $size", size > 0)
        assertTrue("Expected modest PNG size for tiny solid image, got $size", size < 2_000)
    }

    @Test
    fun `encodedSize is larger for noise than for solid color of same dimensions`() {
        val w = 64; val h = 64
        val solidSize = ScreenshotCapture.encodedSize(solid(w, h))
        val noiseSize = ScreenshotCapture.encodedSize(noise(w, h))
        assertTrue(
            "Noise PNG ($noiseSize bytes) should be larger than solid PNG ($solidSize bytes)",
            noiseSize > solidSize,
        )
    }

    // ====================================================================================
    // SECTION 3. fitWithinBudget — no downscaling needed
    // ====================================================================================

    @Test
    fun `image within budget returns original and no warning`() {
        val img = solid(80, 60, Color.GREEN)
        val (result, warning) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = 10_000_000, maxAttempts = 4)
        assertSame("Expected the original image unmodified", img, result)
        assertNull("Expected no warning when under budget", warning)
    }

    @Test
    fun `image within budget at the boundary returns original`() {
        // budgetBytes == encodedSize → the loop condition `> budget` is false, so no
        // downscale, no warning, original returned.
        val img = solid(40, 40)
        val size = ScreenshotCapture.encodedSize(img)
        val (result, warning) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = size, maxAttempts = 4)
        assertSame(img, result)
        assertNull(warning)
    }

    // ====================================================================================
    // SECTION 4. fitWithinBudget — downscaling
    // ====================================================================================

    @Test
    fun `image over budget downscaled once`() {
        val img = noise(128, 128)
        val originalSize = ScreenshotCapture.encodedSize(img)
        // Pick a budget that the original blows past but a single 50% scale comfortably
        // fits under — solid/noise halves should cut size by ~4x for raw bitmap, less for
        // PNG. We aim for "just below original" so exactly one pass is sufficient.
        val budget = originalSize - 1
        val (result, warning) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = budget, maxAttempts = 4)
        assertNotSame("Expected a different (downscaled) image", img, result)
        assertEquals(64, result.width)
        assertEquals(64, result.height)
        val msg = requireNotNull(warning) { "Expected a warning when image was downscaled" }
        assertTrue("warning should mention '1 halving passes', got: $msg", msg.contains("1 halving passes"))
    }

    @Test
    fun `image far over budget downscaled multiple times until under budget`() {
        // Drive the loop with a deliberately tiny budget so multiple halves are needed.
        val img = noise(256, 256)
        // 1 byte budget — only stops when we hit maxAttempts. We *want* to hit maxAttempts
        // here, but also verify the warning's "N halving passes" matches the cap.
        val (result, warning) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = 1, maxAttempts = 3)
        // After 3 halves: 256 -> 128 -> 64 -> 32 px each axis.
        assertEquals(32, result.width)
        assertEquals(32, result.height)
        val msg = requireNotNull(warning) { "Expected a warning when image was downscaled" }
        assertTrue(
            "Expected warning to mention 3 halving passes, got: $msg",
            msg.contains("3 halving passes"),
        )
    }

    @Test
    fun `attempts cap stops downscaling even if still over budget`() {
        val img = noise(64, 64)
        val (result, warning) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = 1, maxAttempts = 2)
        // After exactly 2 halves: 64 -> 32 -> 16.
        assertEquals(16, result.width)
        assertEquals(16, result.height)
        val msg = requireNotNull(warning) { "Expected a warning when downscale capped out" }
        assertTrue(
            "Expected warning to mention exactly 2 halving passes, got: $msg",
            msg.contains("2 halving passes"),
        )
        // And the PNG is still over budget — proving the cap, not the budget, ended the loop.
        assertTrue(
            "Expected result PNG to remain over budget of 1 byte",
            ScreenshotCapture.encodedSize(result) > 1,
        )
    }

    @Test
    fun `maxAttempts zero never downscales even when over budget`() {
        val img = noise(64, 64)
        val (result, warning) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = 1, maxAttempts = 0)
        assertSame("Expected the original image when maxAttempts is 0", img, result)
        assertNull("Expected no warning when no halving passes happened", warning)
    }

    // ====================================================================================
    // SECTION 5. Scaling dimensions sanity
    // ====================================================================================

    @Test
    fun `scale halves dimensions each pass`() {
        // Force exactly N halving passes via tiny budget, then verify width/height pairs.
        val passes = 3
        val img = noise(200, 100)
        val (result, _) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = 1, maxAttempts = passes)
        // scaleImage uses `(src.width * 0.5).toInt()`, max(1, ...). Applied three times:
        //   width:  200 -> 100 -> 50 -> 25
        //   height: 100 -> 50  -> 25 -> 12
        assertEquals(25, result.width)
        assertEquals(12, result.height)
    }

    @Test
    fun `scale never produces a zero-sized image`() {
        // Tiny start + many halves would mathematically reach 0 — scaleImage clamps to 1.
        val img = noise(4, 4)
        val (result, _) = ScreenshotCapture.fitWithinBudget(img, budgetBytes = 1, maxAttempts = 10)
        assertTrue("Width must be at least 1", result.width >= 1)
        assertTrue("Height must be at least 1", result.height >= 1)
        // After many halves of a 4x4 it should bottom out at 1x1.
        assertEquals(1, result.width)
        assertEquals(1, result.height)
        // Avoid an unused-import warning while keeping a clear intent.
        assertEquals(max(1, 1), result.width)
    }
}
