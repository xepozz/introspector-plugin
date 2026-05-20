package com.github.xepozz.introspectorplugin.core

import com.github.xepozz.introspectorplugin.util.MAX_IMAGE_BYTES
import com.github.xepozz.introspectorplugin.util.scaleImage
import com.intellij.openapi.wm.WindowManager
import java.awt.Component
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.Rectangle
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
    fun fitWithinBudget(image: BufferedImage): Pair<BufferedImage, String?> {
        var current = image
        var warning: String? = null
        var attempts = 0
        while (encodedSize(current) > MAX_IMAGE_BYTES && attempts < 4) {
            current = scaleImage(current, 0.5)
            attempts++
            warning = "Image was downscaled to fit MCP response size budget (${attempts} halving passes)."
        }
        return current to warning
    }

    private fun encodedSize(image: BufferedImage): Int {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.size()
    }
}
