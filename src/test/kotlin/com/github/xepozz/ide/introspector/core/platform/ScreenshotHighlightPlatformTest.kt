package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ComponentRegistry
import com.github.xepozz.ide.introspector.core.ScreenshotCapture
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JFrame

/**
 * Platform-level coverage for the `screenshot.highlight` overlay path.
 *
 * Most overlay behaviour (clip, marker, stroke colour) is exercised by the pure-CPU
 * [com.github.xepozz.ide.introspector.core.ScreenshotHighlightTest]. This class wires a
 * realised [JFrame] with a [JButton] so we can verify the end-to-end flow:
 *
 *   1. Register the button in [ComponentRegistry].
 *   2. Capture the button (target='component' equivalent).
 *   3. Draw a highlight that fills the captured image.
 *   4. Decode the result and assert the box stroke appears in the expected zone.
 *
 * Tests use a synthetic JFrame rather than [com.intellij.openapi.wm.WindowManager] —
 * the latter is platform-only but the IDE frame is rarely realised in headless CI, so the
 * test would always early-return. A synthetic JFrame is realised on the EDT here and
 * makes the assertions deterministic.
 */
class ScreenshotHighlightPlatformTest : BasePlatformTestCase() {

    fun testHighlightAroundComponentBoundsPaintsStroke() {
        onEdt {
            val frame = JFrame().apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                setSize(200, 200)
            }
            val button = JButton("OK").apply {
                isOpaque = true
                background = Color.WHITE
                setBounds(0, 0, 100, 40)
            }
            frame.contentPane.add(button)
            frame.pack()
            button.size = java.awt.Dimension(100, 40)
            try {
                val registry = ComponentRegistry.getInstance()
                val id = registry.register(button)
                assertNotNull(id)

                val captured = ScreenshotCapture.captureComponent(button)
                assertTrue("captured image must have positive width", captured.width > 0)
                assertTrue("captured image must have positive height", captured.height > 0)

                val res = ScreenshotCapture.drawHighlight(
                    base = captured,
                    bounds = Rectangle(0, 0, captured.width, captured.height),
                    color = Color.RED,
                    thickness = 3,
                )
                // No clip/outside warning when the box fills the captured area.
                assertTrue(
                    "unexpected warnings: ${res.warnings}",
                    res.warnings.none { it.contains("outside") || it.contains("zero-area") }
                )
                // At least one red-dominant pixel anywhere in the output.
                var foundRed = false
                outer@ for (y in 0 until res.image.height) {
                    for (x in 0 until res.image.width) {
                        val argb = res.image.getRGB(x, y)
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        if (r > 200 && g < 80 && b < 80) { foundRed = true; break@outer }
                    }
                }
                assertTrue("expected at least one red pixel from the highlight stroke", foundRed)
            } finally {
                frame.dispose()
            }
        }
    }

    fun testHighlightOutsideImageEmitsWarning() {
        onEdt {
            val base = java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics()
                try { g.color = Color.WHITE; g.fillRect(0, 0, 40, 40) } finally { g.dispose() }
            }
            val res = ScreenshotCapture.drawHighlight(
                base = base,
                bounds = Rectangle(1000, 1000, 10, 10),
                color = Color.RED,
                thickness = 2,
            )
            assertTrue(
                "expected outside-captured-frame warning, got ${res.warnings}",
                res.warnings.any { it.contains("outside captured frame") }
            )
        }
    }

    private fun onEdt(block: () -> Unit) {
        var thrown: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try { block() } catch (t: Throwable) { thrown = t }
        }
        thrown?.let { throw it }
    }
}
