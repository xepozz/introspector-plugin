package com.github.xepozz.introspectorplugin.util

/**
 * Result of [truncateUtf8]:
 *   - [text]       — the (possibly) shorter string
 *   - [byteLength] — the original total UTF-8 byte length, regardless of truncation
 *   - [truncated]  — `true` when [text] is shorter than the original
 */
data class TruncationResult(val text: String, val byteLength: Int, val truncated: Boolean)

/**
 * Returns [s] (or a shorter prefix) such that its UTF-8 encoding is at most [maxBytes] bytes,
 * never cutting inside a multi-byte code point. Surrogate-pair-aware: a single emoji (4 UTF-8
 * bytes, 2 chars) is either fully included or fully excluded.
 *
 * The fast path — when the string already fits — encodes once to measure length, returns the
 * input verbatim, and reports `truncated = false`. The slow path walks code points to find the
 * largest prefix that fits, then encodes the original once more to report the dropped byte count.
 */
fun truncateUtf8(s: String, maxBytes: Int): TruncationResult {
    require(maxBytes >= 0) { "maxBytes must be non-negative, got $maxBytes" }
    var bytes = 0
    var charsKept = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val cpBytes = utf8ByteLength(cp)
        if (bytes + cpBytes > maxBytes) {
            return TruncationResult(s.substring(0, charsKept), s.toByteArray(Charsets.UTF_8).size, true)
        }
        bytes += cpBytes
        val width = Character.charCount(cp)
        i += width
        charsKept += width
    }
    return TruncationResult(s, bytes, false)
}

private fun utf8ByteLength(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}
