package com.github.xepozz.ide.introspector.toolwindow.details

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Small coloured pill-shaped label used to summarise attributes ("INTERFACE", "BEAN_CLASS",
 * "application", "dynamic", "bundled", …) on detail panels.
 *
 * Rendered as a rounded rectangle with a tinted background — picks a sensible
 * dark-/light-theme-aware colour from [palette]. Falls back to neutral gray.
 */
class Chip(text: String, color: Color) : JLabel(text) {
    init {
        isOpaque = false
        background = color
        foreground = onColor(color)
        font = font.deriveFont(font.size2D - 1f)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val ra = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = background
        g2.fillRoundRect(0, 0, width, height, height, height)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, ra)
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val sz = super.getPreferredSize()
        return Dimension(sz.width, sz.height + 2)
    }

    companion object {
        // Theme-aware palette. JBColor accepts (light, dark) so each chip flips background
        // appropriately. Foreground is picked by luminance via [onColor].
        val BLUE = JBColor(Color(0x2864B8), Color(0x3B7DD8))
        val GREEN = JBColor(Color(0x2E8B57), Color(0x3EA76A))
        val PURPLE = JBColor(Color(0x7C3AED), Color(0x9D6EFA))
        val ORANGE = JBColor(Color(0xC55A11), Color(0xD97742))
        val GRAY = JBColor(Color(0x707070), Color(0x9AA0A6))
        val RED = JBColor(Color(0xC23030), Color(0xE05858))

        /** Picks black or white text depending on the background brightness. */
        private fun onColor(c: Color): Color {
            val lum = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0
            return if (lum > 0.55) Color(0x1F2328) else Color.WHITE
        }
    }
}

/** Domain helpers — keep the colour choices in one place so the panel views stay readable. */
object Chips {
    fun forKind(kind: String): Chip = when (kind.uppercase()) {
        "INTERFACE" -> Chip("INTERFACE", Chip.BLUE)
        "BEAN_CLASS" -> Chip("BEAN_CLASS", Chip.PURPLE)
        else -> Chip(kind, Chip.GRAY)
    }

    fun forArea(area: String): Chip = when (area.lowercase()) {
        "application" -> Chip("application", Chip.BLUE)
        "project" -> Chip("project", Chip.PURPLE)
        else -> Chip(area, Chip.GRAY)
    }

    fun dynamic(isDynamic: Boolean): Chip =
        if (isDynamic) Chip("dynamic", Chip.GREEN) else Chip("static", Chip.GRAY)

    fun bundled(): Chip = Chip("bundled", Chip.GRAY)

    fun enabled(isEnabled: Boolean): Chip =
        if (isEnabled) Chip("enabled", Chip.GREEN) else Chip("disabled", Chip.RED)

    fun optional(isOptional: Boolean): Chip =
        if (isOptional) Chip("optional", Chip.ORANGE) else Chip("required", Chip.BLUE)

    fun count(label: String, n: Int): Chip = Chip("$label: $n", Chip.GRAY)
}

/** Convenience to wrap chips into a left-aligned FlowLayout strip used in panel headers. */
class ChipStrip private constructor(chips: List<JComponent>) : JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)) {

    init {
        isOpaque = false
        for (c in chips) {
            c.alignmentY = Component.CENTER_ALIGNMENT
            add(c)
        }
    }

    companion object {
        operator fun invoke(vararg chips: JComponent): ChipStrip = ChipStrip(chips.toList())
        operator fun invoke(chips: List<JComponent>): ChipStrip = ChipStrip(chips)
    }
}
