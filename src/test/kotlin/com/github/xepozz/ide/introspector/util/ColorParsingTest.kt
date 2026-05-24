package com.github.xepozz.ide.introspector.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color

/**
 * Unit tests for [parseCssColor].
 *
 * Covers all three hex forms (#RGB, #RRGGBB, #RRGGBBAA), case-insensitive named colours,
 * and graceful null-return for garbage input. Used by the `screenshot.highlight` and
 * `screenshot.diff` tools — invalid input is never thrown, callers fall back to red and
 * emit a warning.
 */
class ColorParsingTest {

    @Test fun `null input returns null`() {
        assertNull(parseCssColor(null))
    }

    @Test fun `blank input returns null`() {
        assertNull(parseCssColor(""))
        assertNull(parseCssColor("   "))
    }

    @Test fun `hash without digits returns null`() {
        assertNull(parseCssColor("#"))
    }

    @Test fun `long hex RRGGBB parses red`() {
        val c = parseCssColor("#FF0000")
        assertNotNull(c)
        assertEquals(Color(255, 0, 0), c)
    }

    @Test fun `long hex RRGGBB parses blue case insensitive`() {
        assertEquals(Color(0x00, 0x00, 0xFF), parseCssColor("#0000ff"))
        assertEquals(Color(0x00, 0x00, 0xFF), parseCssColor("#0000FF"))
    }

    @Test fun `short hex 3 char expands per CSS rule`() {
        // #F00 -> #FF0000
        assertEquals(Color(255, 0, 0), parseCssColor("#F00"))
        // #ABC -> #AABBCC
        assertEquals(Color(0xAA, 0xBB, 0xCC), parseCssColor("#abc"))
    }

    @Test fun `hex 8 RRGGBBAA includes alpha`() {
        val c = parseCssColor("#FF0000FF")
        assertNotNull(c)
        assertEquals(255, c!!.alpha)
        assertEquals(255, c.red)

        val half = parseCssColor("#00FF0080")
        assertNotNull(half)
        assertEquals(0x80, half!!.alpha)
        assertEquals(255, half.green)
    }

    @Test fun `hex with non hex digit returns null`() {
        assertNull(parseCssColor("#GG0000"))
        assertNull(parseCssColor("#XYZ"))
    }

    @Test fun `hex of unusual length returns null`() {
        assertNull(parseCssColor("#1234"))
        assertNull(parseCssColor("#12345"))
        assertNull(parseCssColor("#1234567"))
        assertNull(parseCssColor("#123456789"))
    }

    @Test fun `named red parses to red regardless of case`() {
        assertEquals(Color.RED, parseCssColor("red"))
        assertEquals(Color.RED, parseCssColor("RED"))
        assertEquals(Color.RED, parseCssColor("Red"))
    }

    @Test fun `named lime maps to pure green`() {
        // CSS distinguishes 'green' (#008000) from 'lime' (#00FF00).
        assertEquals(Color(0, 255, 0), parseCssColor("lime"))
        assertEquals(Color(0, 128, 0), parseCssColor("green"))
    }

    @Test fun `named gray and grey both work`() {
        assertEquals(Color(128, 128, 128), parseCssColor("gray"))
        assertEquals(Color(128, 128, 128), parseCssColor("grey"))
    }

    @Test fun `unknown named color returns null`() {
        assertNull(parseCssColor("octarine"))
        assertNull(parseCssColor("garbage"))
    }

    @Test fun `leading and trailing whitespace tolerated`() {
        assertEquals(Color.RED, parseCssColor("  red  "))
        assertEquals(Color(255, 0, 0), parseCssColor("\t#FF0000\n"))
    }
}
