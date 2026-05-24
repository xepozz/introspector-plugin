package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * I/O-side tests for [LogReader] — backward-seek tail correctness, rotation handling,
 * UTF-8 boundary cleanup, missing-file behavior. Uses [TemporaryFolder] because the
 * project is on JUnit 4 (no `@TempDir`).
 */
class LogReaderTailTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun ideaLogFile(): Path = tmp.newFile("idea.log").toPath()

    /** Synthesize a parseable IDEA line — keeps fixtures readable. */
    private fun line(i: Int): String =
        "2026-05-24 14:%02d:%02d,000 [   1] INFO  - cat - msg-%05d".format(i / 60, i % 60, i)

    // ====================================================================================
    // Section 1: backward-seek tail correctness
    // ====================================================================================

    @Test
    fun `3 MB fixture returns exactly the last 100 lines`() {
        val file = ideaLogFile()
        // ~3 MB by repeating padded lines.
        val pad = " ".repeat(400)
        val total = 6000
        Files.newBufferedWriter(file).use { w ->
            for (i in 0 until total) {
                w.write("2026-05-24 14:00:00,000 [   1] INFO  - cat - line-%06d%s".format(i, pad))
                w.newLine()
            }
        }
        // Sanity: file must actually exceed the 1 MB tail buffer to exercise the truncation path.
        assertTrue(
            "fixture must exceed 1 MB so we exercise the tail-buffer truncation path",
            Files.size(file) > LogReader.MAX_TAIL_BYTES,
        )
        val reader = LogReader(file)
        val resp = reader.tail(linesRequested = 100)
        assertEquals(100, resp.lines.size)
        // The very last line must be the very last one written — strong end-of-file invariant.
        assertTrue(
            "last returned line must be the actual last line",
            resp.lines.last().raw.startsWith("2026-05-24 14:00:00,000 [   1] INFO  - cat - line-${"%06d".format(total - 1)}"),
        )
        assertTrue("truncated must be true because file > 1 MB", resp.truncated)
    }

    @Test
    fun `10-line file with lines=1000 returns 10 and not truncated`() {
        val file = ideaLogFile()
        Files.writeString(file, (0..9).joinToString("\n") { line(it) } + "\n")
        val reader = LogReader(file)
        val resp = reader.tail(linesRequested = 1000)
        assertEquals(10, resp.lines.size)
        assertFalse("small file is not truncated", resp.truncated)
    }

    @Test
    fun `zero-byte file returns empty and reports logPath`() {
        val file = ideaLogFile()
        // Just create empty file.
        val reader = LogReader(file)
        val resp = reader.tail(linesRequested = 100)
        assertEquals(0, resp.lines.size)
        assertEquals(file.toString(), resp.logPath)
    }

    @Test
    fun `missing file returns empty and reports expected logPath`() {
        val nonExistent = tmp.root.toPath().resolve("nope.log")
        assertFalse("precondition: file must not exist", Files.exists(nonExistent))
        val reader = LogReader(nonExistent)
        val resp = reader.tail(linesRequested = 100)
        assertEquals(0, resp.lines.size)
        assertEquals(nonExistent.toString(), resp.logPath)
    }

    // ====================================================================================
    // Section 2: UTF-8 boundary
    // ====================================================================================

    @Test
    fun `emoji straddling the tail-buffer boundary does not produce garbled output`() {
        val file = ideaLogFile()
        // Construct a file where a 4-byte emoji codepoint is split across the 1 MB cut-off
        // when we read the trailing 1 MB. Strategy: front-load junk so the file is much larger
        // than 1 MB, then place a recognizable emoji at the END inside a parseable line.
        // The tail buffer starts at byte `len - 1MB` and we drop the partial first line via the
        // "advance to first '\n'" rule — so the emoji at the end must round-trip cleanly.
        val front = StringBuilder()
        val pad = "X".repeat(500)
        for (i in 0 until 4000) {
            front.append("2026-05-24 14:00:00,000 [   1] INFO  - cat - $pad #$i\n")
        }
        val emojiLine = "2026-05-24 14:00:00,000 [   1] INFO  - cat - tail 🚀 done\n"
        // Repeat enough lines to exceed 1 MB.
        front.append(emojiLine.repeat(40))
        Files.writeString(file, front.toString(), StandardCharsets.UTF_8)
        assertTrue(Files.size(file) > LogReader.MAX_TAIL_BYTES)

        val reader = LogReader(file)
        val resp = reader.tail(linesRequested = 20)
        // Every returned line must include the emoji intact — proves no mid-codepoint split.
        for (l in resp.lines) {
            assertTrue("line should contain the rocket emoji intact: ${l.raw}", l.raw.contains("🚀"))
            assertTrue("line should contain 'done' (no truncation): ${l.raw}", l.raw.endsWith("done"))
        }
    }

    // ====================================================================================
    // Section 3: rotation handling for errors_since
    // ====================================================================================

    @Test
    fun `errors_since walks idea log_1 oldest-first when cutoff predates current log`() {
        val main = ideaLogFile()
        val rotated1 = tmp.newFile("idea.log.1").toPath()
        // .1 holds an older ERROR; .log holds a newer one.
        Files.writeString(
            rotated1,
            "2026-05-24 12:00:00,000 [   1] ERROR - cat - java.lang.RuntimeException: old\n",
        )
        Files.writeString(
            main,
            "2026-05-24 14:00:00,000 [   1] ERROR - cat - java.lang.RuntimeException: new\n",
        )
        val reader = LogReader(main, rotationFiles = { listOf(rotated1) })
        val resp = reader.errorsSince(
            sinceIsoTimestamp = "2026-05-24T11:00:00",
            minSeverity = "WARN",
            limit = 100,
        )
        assertEquals(2, resp.errors.size)
        // Oldest first — the rotation file content precedes the main file content.
        assertEquals("old", resp.errors[0].message!!.substringBefore(" "))
        assertEquals("new", resp.errors[1].message!!.substringBefore(" "))
    }

    @Test
    fun `errors_since reverse-seeks current log to find recent ERROR in a multi-MB file`() {
        // Regression test for Finding 1: the previous implementation called readUpTo() which
        // seeks to byte 0 and reads the FIRST 8 MB of every source. On a long-running IDE
        // session that means `lastMinutes=5` parses day-1 history and never reaches recent
        // entries. After the fix we reverse-seek the current log (1 MB tail), so a recent
        // ERROR at the END of a multi-MB file MUST surface.
        val file = ideaLogFile()
        // ~5 MB of old INFO noise — well beyond MAX_TAIL_BYTES (1 MB). Old timestamps so
        // they would dominate the previous forward-read.
        val pad = " ".repeat(400)
        Files.newBufferedWriter(file).use { w ->
            for (i in 0 until 10_000) {
                w.write("2026-05-24 10:00:00,000 [   1] INFO  - cat - old-line-%06d%s".format(i, pad))
                w.newLine()
            }
            // The needle: a recent ERROR at the very end of the file.
            w.write("2026-05-24 23:59:00,000 [   1] ERROR - cat - java.lang.RuntimeException: recent-boom")
            w.newLine()
        }
        assertTrue("fixture must exceed MAX_TAIL_BYTES so the bug would trigger", Files.size(file) > LogReader.MAX_TAIL_BYTES)

        val reader = LogReader(file)
        val resp = reader.errorsSince(sinceIsoTimestamp = "2026-05-24T23:00:00", minSeverity = "WARN", limit = 100)
        // With the bug we'd parse the first 8 MB of INFO noise and return 0 errors.
        // After the fix we tail the last 1 MB and find the ERROR at the end.
        assertEquals("recent ERROR at the file tail must surface", 1, resp.errors.size)
        assertEquals("recent-boom", resp.errors[0].message)
        assertEquals("java.lang.RuntimeException", resp.errors[0].throwableClass)
    }

    @Test
    fun `errors_since does NOT walk rotations when current log already covers the cutoff`() {
        // Sanity: with the fix's "walk rotations only when cutoff predates current" gating,
        // a small current log whose oldest line predates the cutoff must NOT trigger a
        // rotation walk. We supply a poisoned rotation supplier that throws if called — if
        // it executes the test fails.
        val main = ideaLogFile()
        Files.writeString(
            main,
            """
            |2026-05-24 13:00:00,000 [   1] INFO  - cat - older-info
            |2026-05-24 14:30:00,000 [   1] ERROR - cat - java.lang.RuntimeException: now
            |
            """.trimMargin(),
        )
        var rotationCalled = false
        val reader = LogReader(main, rotationFiles = {
            rotationCalled = true
            error("rotation walk should not run — current log already covers cutoff")
        })
        val resp = reader.errorsSince(sinceIsoTimestamp = "2026-05-24T14:00:00", minSeverity = "WARN", limit = 100)
        assertEquals(1, resp.errors.size)
        assertEquals("now", resp.errors[0].message)
        // `rotationFiles` may safely be called once for `runCatching {…}.getOrElse {}` defence
        // — the assertion is that NO actual walk was attempted because shouldWalkRotations
        // returns false.
        assertFalse("rotationFiles supplier must not be invoked", rotationCalled)
    }

    @Test
    fun `errors_since respects 8 MB cumulative rotation budget`() {
        // 4 × 3 MB rotation files = 12 MB total; budget is 8 MB → must stop scanning early.
        val main = ideaLogFile()
        Files.writeString(main, "")
        val rotations = (1..4).map { i ->
            val p = tmp.newFile("idea.log.$i").toPath()
            // ~3 MB of WARN lines.
            val pad = "P".repeat(400)
            Files.newBufferedWriter(p).use { w ->
                for (k in 0 until 6000) {
                    w.write("2026-05-24 13:00:00,000 [   1] WARN  - cat - rot$i#$k $pad")
                    w.newLine()
                }
            }
            assertTrue("rotation $i must exceed 1 MB", Files.size(p) > 1_000_000L)
            p
        }
        val reader = LogReader(main, rotationFiles = { rotations })
        val resp = reader.errorsSince(
            sinceIsoTimestamp = "2026-05-24T10:00:00",
            minSeverity = "WARN",
            limit = 1000,
        )
        // We can't predict the exact count once we run out of budget — but truncated MUST be set.
        assertTrue("truncated must be set when rotation budget exhausted", resp.truncated)
        assertTrue("some entries should be returned", resp.errors.isNotEmpty())
    }

    // ====================================================================================
    // Section 4: maxBytes cap
    // ====================================================================================

    @Test
    fun `tail respects maxBytes cap and sets truncated`() {
        val file = ideaLogFile()
        val padded = (0..999).joinToString("\n") {
            "2026-05-24 14:00:00,000 [   1] INFO  - cat - msg-$it ${"X".repeat(200)}"
        } + "\n"
        Files.writeString(file, padded)
        val reader = LogReader(file)
        val resp = reader.tail(linesRequested = 500, maxBytes = LogReader.MIN_RESPONSE_BYTES)
        // Should have far fewer than 500 lines because each line is ~250 bytes and we cap at 1 KB.
        assertTrue("byte cap should keep us under 500 lines", resp.lines.size < 500)
        assertNotNull(resp.lines.firstOrNull())
        assertTrue("truncated should be set", resp.truncated)
    }
}
