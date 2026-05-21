package com.github.xepozz.ide.introspector.util

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Encodes [image] as base64-PNG. Returns the encoded string. */
fun encodePngBase64(image: BufferedImage): String {
    val baos = ByteArrayOutputStream()
    ImageIO.write(image, "png", baos)
    return Base64.getEncoder().encodeToString(baos.toByteArray())
}

/** Returns a scaled copy of [src]. If [factor]≈1.0 returns [src] unchanged. */
fun scaleImage(src: BufferedImage, factor: Double): BufferedImage {
    if (kotlin.math.abs(factor - 1.0) < 1e-3) return src
    val w = kotlin.math.max(1, (src.width * factor).toInt())
    val h = kotlin.math.max(1, (src.height * factor).toInt())
    val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g: Graphics2D = dst.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
    } finally {
        g.dispose()
    }
    return dst
}

const val MAX_IMAGE_BYTES = 1_500_000
