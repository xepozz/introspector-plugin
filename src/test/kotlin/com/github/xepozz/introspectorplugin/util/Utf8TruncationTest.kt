package com.github.xepozz.introspectorplugin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [truncateUtf8]. The function is the byte-budget enforcer for `code.get_source`
 * which exposes user-supplied class text over the MCP wire — getting this wrong silently
 * corrupts UTF-8 (U+FFFD replacement characters) when a class file contains non-ASCII chars
 * (Cyrillic identifiers, emoji in string literals, etc.). The pre-fix implementation
 * indexed `bytes[cut]` instead of `bytes[cut-1]` and could land mid-sequence.
 */
class Utf8TruncationTest {

    @Test
    fun `short pure-ASCII string is returned unchanged`() {
        val r = truncateUtf8("hello", 1024)
        assertEquals("hello", r.text)
        assertEquals(5, r.byteLength)
        assertFalse(r.truncated)
    }

    @Test
    fun `pure-ASCII string truncates exactly at maxBytes`() {
        val r = truncateUtf8("abcdef", 3)
        assertEquals("abc", r.text)
        assertEquals(6, r.byteLength)
        assertTrue(r.truncated)
    }

    @Test
    fun `cyrillic-only string never cuts inside a 2-byte sequence`() {
        // Each Cyrillic letter is 2 bytes in UTF-8. Budget 5 has room for 2 letters (4 bytes)
        // — adding a third would land us at 6 bytes, mid-budget. The buggy implementation
        // sliced bytes[0,5), then decode produced "Пр�".
        val src = "Привет"
        val r = truncateUtf8(src, 5)
        assertEquals("Пр", r.text)
        assertEquals(4, r.text.toByteArray(Charsets.UTF_8).size)
        assertTrue(r.truncated)
        assertEquals(12, r.byteLength)
    }

    @Test
    fun `mixed ASCII and Cyrillic respects boundary`() {
        val src = "abс"   // 'a'=1B 'b'=1B 'с'=2B  — 4 bytes total
        val r3 = truncateUtf8(src, 3)
        assertEquals("ab", r3.text)   // can't fit 'с' (2 more bytes) into 3 with 2 used
        assertTrue(r3.truncated)
        val r4 = truncateUtf8(src, 4)
        assertEquals(src, r4.text)
        assertFalse(r4.truncated)
    }

    @Test
    fun `emoji surrogate pair is treated as a single code point`() {
        // U+1F600 GRINNING FACE: 1 code point, 2 UTF-16 chars, 4 UTF-8 bytes.
        val emoji = "😀"
        val r3 = truncateUtf8(emoji, 3)
        assertEquals("", r3.text)    // doesn't fit in 3 bytes
        assertTrue(r3.truncated)
        val r4 = truncateUtf8(emoji, 4)
        assertEquals(emoji, r4.text)
        assertFalse(r4.truncated)
    }

    @Test
    fun `empty string yields empty result with zero bytes`() {
        val r = truncateUtf8("", 100)
        assertEquals("", r.text)
        assertEquals(0, r.byteLength)
        assertFalse(r.truncated)
    }

    @Test
    fun `zero budget returns empty string when input non-empty`() {
        val r = truncateUtf8("abc", 0)
        assertEquals("", r.text)
        assertEquals(3, r.byteLength)
        assertTrue(r.truncated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative budget is rejected`() {
        truncateUtf8("abc", -1)
    }

    @Test
    fun `truncated result never produces U+FFFD replacement chars`() {
        // Regression for the buggy bytes[cut] index. Any cut budget over a Cyrillic string
        // used to land mid-sequence, decode would replace with U+FFFD on round-trip.
        val src = "АБВГДЕЁЖЗИЙКЛМНОП"
        for (budget in 0..src.toByteArray(Charsets.UTF_8).size) {
            val r = truncateUtf8(src, budget)
            assertFalse(
                "Budget $budget produced replacement char: '${r.text}'",
                r.text.contains('�'),
            )
            // And the result is itself a clean prefix.
            assertTrue(src.startsWith(r.text))
        }
    }
}
