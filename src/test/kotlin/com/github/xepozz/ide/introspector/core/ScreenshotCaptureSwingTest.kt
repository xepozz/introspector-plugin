package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JButton

/**
 * Tests for [ScreenshotCapture.captureComponent].
 *
 * Restricted to the off-screen `paint()` path because the other public methods rely on
 * `java.awt.Robot` (needs a display) and `WindowManager` (needs an IDE), neither of which
 * is available in a headless unit-test JVM. `fitWithinBudget` is covered separately.
 *
 * All Swing work runs on the EDT via [onEdt].
 */
class ScreenshotCaptureSwingTest {

    // ====================================================================================
    // SECTION 1. Dimensions
    // ====================================================================================

    @Test
    fun `capture returns BufferedImage matching component dimensions`() {
        val image = onEdt {
            val button = JButton("x").apply { setBounds(0, 0, 200, 100) }
            ScreenshotCapture.captureComponent(button)
        }
        assertEquals(200, image.width)
        assertEquals(100, image.height)
    }

    @Test
    fun `capture clamps a zero-size component to 1x1`() {
        val image = onEdt {
            // A freshly constructed JButton has width=0/height=0 until it's added to a
            // realised container. captureComponent guards with max(1, ...) so we still
            // produce a 1x1 image rather than throwing.
            val button = JButton("x")
            ScreenshotCapture.captureComponent(button)
        }
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }

    // ====================================================================================
    // SECTION 2. Image type
    // ====================================================================================

    @Test
    fun `captured image type is TYPE_INT_ARGB`() {
        val image = onEdt {
            val button = JButton("x").apply { setBounds(0, 0, 10, 10) }
            ScreenshotCapture.captureComponent(button)
        }
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.type)
    }

    // ====================================================================================
    // SECTION 3. Painting
    // ====================================================================================

    @Test
    fun `capture produces non-blank pixels for a component painted in a color`() {
        val image = onEdt {
            val button = JButton("x").apply {
                isOpaque = true
                background = Color.RED
                setBounds(0, 0, 50, 50)
            }
            ScreenshotCapture.captureComponent(button)
        }
        // Look-and-feel can repaint a button using its own colours, so we don't insist on
        // RED specifically — we only verify that *something* was painted (at least one
        // non-transparent pixel anywhere in the image).
        var foundPainted = false
        outer@ for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xFF
                if (alpha != 0) {
                    foundPainted = true
                    break@outer
                }
            }
        }
        assertTrue("captured image must contain at least one non-transparent pixel", foundPainted)
    }
}
