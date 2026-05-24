package com.github.xepozz.ide.introspector.util

import java.awt.Color

/**
 * Parses a CSS-style color string into an AWT [Color].
 *
 * Accepted forms:
 *   - `#RGB`       — short hex, e.g. `#F00` → red
 *   - `#RRGGBB`    — 24-bit hex
 *   - `#RRGGBBAA`  — 32-bit hex with trailing alpha
 *   - case-insensitive CSS named color (a small but standard subset)
 *
 * Returns `null` for unrecognised input so callers can fall back to a default and emit a
 * warning rather than throwing.
 */
fun parseCssColor(raw: String?): Color? {
    if (raw == null) return null
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (s.startsWith("#")) return parseHex(s.substring(1))
    return NAMED_COLORS[s.lowercase()]
}

private fun parseHex(hex: String): Color? {
    if (hex.isEmpty()) return null
    if (!hex.all { it.isHexDigit() }) return null
    return when (hex.length) {
        3 -> {
            val r = expand(hex[0])
            val g = expand(hex[1])
            val b = expand(hex[2])
            Color(r, g, b)
        }
        6 -> {
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            Color(r, g, b)
        }
        8 -> {
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            val a = hex.substring(6, 8).toInt(16)
            Color(r, g, b, a)
        }
        else -> null
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun expand(c: Char): Int {
    val v = Character.digit(c, 16)
    return (v shl 4) or v
}

/**
 * Minimal CSS named-color subset — matches the most common references and the ones
 * documented in the tool description. Add to it only when needed.
 */
private val NAMED_COLORS: Map<String, Color> = mapOf(
    "black"   to Color(0, 0, 0),
    "white"   to Color(255, 255, 255),
    "red"     to Color(255, 0, 0),
    "green"   to Color(0, 128, 0),
    "lime"    to Color(0, 255, 0),
    "blue"    to Color(0, 0, 255),
    "yellow"  to Color(255, 255, 0),
    "cyan"    to Color(0, 255, 255),
    "aqua"    to Color(0, 255, 255),
    "magenta" to Color(255, 0, 255),
    "fuchsia" to Color(255, 0, 255),
    "gray"    to Color(128, 128, 128),
    "grey"    to Color(128, 128, 128),
    "silver"  to Color(192, 192, 192),
    "maroon"  to Color(128, 0, 0),
    "olive"   to Color(128, 128, 0),
    "purple"  to Color(128, 0, 128),
    "teal"    to Color(0, 128, 128),
    "navy"    to Color(0, 0, 128),
    "orange"  to Color(255, 165, 0),
    "pink"    to Color(255, 192, 203),
)
