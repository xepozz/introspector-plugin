package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Unit tests for [ImageDiffer]. Pure CPU, no IntelliJ runtime required.
 *
 * The diff algorithm is per-channel ARGB with a clamped tolerance. Tests target the
 * boundary conditions: identical inputs, single-pixel changes, block changes, tolerance
 * masking, size-mismatch policies and alpha-vs-RGB diffing.
 */
class ImageDifferTest {

    // ====================================================================================
    // SECTION 1. Helpers
    // ====================================================================================

    private fun solid(w: Int, h: Int, argb: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb)
        return img
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    // ====================================================================================
    // SECTION 2. Identical inputs
    // ====================================================================================

    @Test fun `identical images produce zero diff and null bbox`() {
        val a = solid(8, 8, rgba(10, 20, 30))
        val b = solid(8, 8, rgba(10, 20, 30))
        val r = ImageDiffer.diff(a, b, tolerance = 0)
        assertEquals(64, r.totalPixels)
        assertEquals(0, r.differingPixels)
        assertEquals(0.0, r.diffPercentage, 0.0001)
        assertNull(r.bbox)
        // Composite is still a valid ARGB image of the right size.
        assertEquals(8, r.composite.width)
        assertEquals(8, r.composite.height)
    }

    // ====================================================================================
    // SECTION 3. Single-pixel changes — bbox math
    // ====================================================================================

    @Test fun `single pixel change yields one differing pixel and 1x1 bbox`() {
        val a = solid(8, 8, rgba(0, 0, 0))
        val b = solid(8, 8, rgba(0, 0, 0))
        b.setRGB(3, 5, rgba(255, 255, 255))
        val r = ImageDiffer.diff(a, b, tolerance = 0)
        assertEquals(1, r.differingPixels)
        val bbox = r.bbox
        assertNotNull(bbox)
        assertEquals(3, bbox!!.x)
        assertEquals(5, bbox.y)
        assertEquals(1, bbox.width)
        assertEquals(1, bbox.height)
    }

    @Test fun `3x3 block change at 5,5 produces matching bbox`() {
        val a = solid(16, 16, rgba(0, 0, 0))
        val b = solid(16, 16, rgba(0, 0, 0))
        for (y in 5..7) for (x in 5..7) b.setRGB(x, y, rgba(255, 255, 255))
        val r = ImageDiffer.diff(a, b, tolerance = 0)
        assertEquals(9, r.differingPixels)
        val bbox = r.bbox
        assertNotNull(bbox)
        assertEquals(5, bbox!!.x)
        assertEquals(5, bbox.y)
        assertEquals(3, bbox.width)
        assertEquals(3, bbox.height)
    }

    // ====================================================================================
    // SECTION 4. Tolerance
    // ====================================================================================

    @Test fun `tolerance 10 masks plus 5 per channel`() {
        val a = solid(4, 4, rgba(100, 100, 100))
        val b = solid(4, 4, rgba(105, 105, 105))
        val r = ImageDiffer.diff(a, b, tolerance = 10)
        assertEquals(0, r.differingPixels)
        assertNull(r.bbox)
    }

    @Test fun `tolerance 4 reports plus 5 change in all pixels`() {
        val a = solid(4, 4, rgba(100, 100, 100))
        val b = solid(4, 4, rgba(105, 105, 105))
        val r = ImageDiffer.diff(a, b, tolerance = 4)
        assertEquals(16, r.differingPixels)
        assertEquals(100.0, r.diffPercentage, 0.0001)
    }

    @Test fun `negative tolerance clamps to zero strict mode`() {
        val a = solid(2, 2, rgba(50, 50, 50))
        val b = solid(2, 2, rgba(51, 50, 50))
        val r = ImageDiffer.diff(a, b, tolerance = -100)
        assertEquals(4, r.differingPixels)
    }

    @Test fun `huge tolerance clamps to 255 nothing differs`() {
        val a = solid(2, 2, rgba(0, 0, 0))
        val b = solid(2, 2, rgba(255, 255, 255))
        val r = ImageDiffer.diff(a, b, tolerance = 9999)
        assertEquals(0, r.differingPixels)
    }

    // ====================================================================================
    // SECTION 5. Alpha-channel diff
    // ====================================================================================

    @Test fun `alpha 255 vs 128 counts at tolerance 0`() {
        val a = solid(2, 2, rgba(0, 0, 0, 255))
        val b = solid(2, 2, rgba(0, 0, 0, 128))
        val r = ImageDiffer.diff(a, b, tolerance = 0)
        assertEquals(4, r.differingPixels)
    }

    @Test fun `alpha 255 vs 128 not counted at tolerance 128`() {
        val a = solid(2, 2, rgba(0, 0, 0, 255))
        val b = solid(2, 2, rgba(0, 0, 0, 128))
        val r = ImageDiffer.diff(a, b, tolerance = 128)
        assertEquals(0, r.differingPixels)
    }

    // ====================================================================================
    // SECTION 6. Size-mismatch policy
    // ====================================================================================

    @Test fun `size mismatch with error policy throws`() {
        val a = solid(4, 4, rgba(0, 0, 0))
        val b = solid(8, 8, rgba(0, 0, 0))
        try {
            ImageDiffer.diff(a, b, sizeMismatchPolicy = ImageDiffer.SizeMismatchPolicy.Error)
            fail("Expected SizeMismatchException")
        } catch (e: ImageDiffer.SizeMismatchException) {
            assertTrue("expected size info in message: ${e.message}", (e.message ?: "").contains("differ"))
        }
    }

    @Test fun `size mismatch with resize policy resizes before to after`() {
        val a = solid(2, 2, rgba(0, 0, 0))
        val b = solid(8, 8, rgba(0, 0, 0))
        val r = ImageDiffer.diff(a, b, sizeMismatchPolicy = ImageDiffer.SizeMismatchPolicy.Resize)
        assertEquals(64, r.totalPixels)
        assertEquals(8, r.composite.width)
        assertEquals(8, r.composite.height)
        assertTrue("expected warning about resize", r.warnings.any { it.contains("resized") })
    }

    @Test fun `size mismatch with pad policy expands to max dims`() {
        // 'before' is solid black 4x4; 'after' is solid black 8x8. After padding both to
        // 8x8 the unpadded region matches (both black). The padded region (right & bottom
        // of 'before') gets alpha=0 transparent in 'before' but stays alpha=255 black in
        // 'after' — alpha differs by 255 which is > tolerance 8 → diff counted.
        val a = solid(4, 4, rgba(0, 0, 0, 255))
        val b = solid(8, 8, rgba(0, 0, 0, 255))
        val r = ImageDiffer.diff(a, b, sizeMismatchPolicy = ImageDiffer.SizeMismatchPolicy.Pad)
        assertEquals(64, r.totalPixels)
        // 64 - 16 (the matching 4x4 region) = 48 differing pixels.
        assertEquals(48, r.differingPixels)
        val bbox = r.bbox
        assertNotNull(bbox)
        // bbox covers the entire pad region (right column + bottom rows that aren't already
        // covered). Minimum should include x>=4 OR y>=4.
        assertTrue(bbox!!.width > 0 && bbox.height > 0)
        assertTrue("expected pad warning", r.warnings.any { it.contains("padded") })
    }

    // ====================================================================================
    // SECTION 7. Highlight tint colour applied to composite
    // ====================================================================================

    @Test fun `highlight tint is written into composite at changed pixel`() {
        val a = solid(4, 4, rgba(0, 0, 0))
        val b = solid(4, 4, rgba(0, 0, 0))
        b.setRGB(1, 1, rgba(255, 255, 255))
        val r = ImageDiffer.diff(a, b, tolerance = 0, highlightColor = Color.YELLOW)
        val px = r.composite.getRGB(1, 1)
        val a8 = (px ushr 24) and 0xFF
        val red = (px ushr 16) and 0xFF
        val green = (px ushr 8) and 0xFF
        val blue = px and 0xFF
        assertEquals(255, a8)
        assertEquals(255, red)
        assertEquals(255, green)
        assertEquals(0, blue)
    }

    @Test fun `percentage rounded to 4 decimal places`() {
        // 1 / 7 = 0.142857142... -> as percent = 14.2857142… → rounded to 14.2857
        val a = solid(7, 1, rgba(0, 0, 0))
        val b = solid(7, 1, rgba(0, 0, 0))
        b.setRGB(0, 0, rgba(255, 255, 255))
        val r = ImageDiffer.diff(a, b, tolerance = 0)
        assertEquals(14.2857, r.diffPercentage, 1e-9)
    }

    // ====================================================================================
    // SECTION 8. Non-ARGB inputs
    // ====================================================================================

    @Test fun `non ARGB input gets converted and diffed correctly`() {
        val a = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB) // alpha=opaque after read
        val b = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 4) for (x in 0 until 4) {
            a.setRGB(x, y, rgba(0, 0, 0))
            b.setRGB(x, y, rgba(0, 0, 0))
        }
        b.setRGB(2, 2, rgba(255, 0, 0))
        val r = ImageDiffer.diff(a, b, tolerance = 0)
        assertEquals(1, r.differingPixels)
        assertEquals(2, r.bbox!!.x)
        assertEquals(2, r.bbox!!.y)
    }
}
