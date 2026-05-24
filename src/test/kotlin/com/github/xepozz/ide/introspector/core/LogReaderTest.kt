package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-JVM unit tests for [LogReader] — parsing, filtering, stacktrace grouping,
 * redaction, cutoff time handling. Tests that need an on-disk file use the JUnit 4
 * [TemporaryFolder] rule (project test stack is JUnit 4, not 5 — `@TempDir` would
 * require a JUnit-Jupiter dependency that isn't on the test classpath).
 */
class LogReaderTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun newReader(content: String): LogReader {
        val file = tmp.newFile("idea.log").toPath()
        Files.writeString(file, content)
        return LogReader(file)
    }

    // ====================================================================================
    // Section 1: line parsing
    // ====================================================================================

    @Test
    fun `parses a vanilla IDEA log line into a structured LogLine`() {
        val reader = LogReader(tmp.newFile("idea.log").toPath())
        val raw = "2026-05-24 14:30:00,123 [   42] INFO  - com.foo.Bar - hello world"
        val line = reader.parseLine(raw)
        assertTrue("expected parsed=true for standard layout: $raw", line.parsed)
        assertEquals("2026-05-24 14:30:00,123", line.timestamp)
        assertEquals("42", line.thread)
        assertEquals("INFO", line.severity)
        assertEquals("com.foo.Bar", line.category)
        assertEquals("hello world", line.message)
        assertEquals(raw, line.raw)
    }

    @Test
    fun `treats a tab-prefixed stack frame as parsed=false`() {
        val reader = LogReader(tmp.newFile("idea.log").toPath())
        val raw = "\tat com.foo.Bar.baz(Bar.kt:42)"
        val line = reader.parseLine(raw)
        assertFalse("stack frame must be parsed=false", line.parsed)
        assertNull(line.severity)
        assertNull(line.category)
        assertEquals(raw, line.raw)
    }

    @Test
    fun `treats a non-standard line as parsed=false but keeps raw`() {
        val reader = LogReader(tmp.newFile("idea.log").toPath())
        val raw = "this is just println output with no timestamp"
        val line = reader.parseLine(raw)
        assertFalse(line.parsed)
        assertEquals(raw, line.raw)
    }

    // ====================================================================================
    // Section 2: severity / category / regex filters
    // ====================================================================================

    @Test
    fun `severity WARN excludes INFO and DEBUG`() {
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] INFO  - cat - info
            |2026-05-24 14:30:00,001 [   1] DEBUG - cat - debug
            |2026-05-24 14:30:00,002 [   1] WARN  - cat - warn
            |2026-05-24 14:30:00,003 [   1] ERROR - cat - error
            |
            """.trimMargin(),
        )
        val resp = reader.tail(linesRequested = 10, severity = "WARN")
        assertEquals(2, resp.lines.size)
        assertEquals(listOf("WARN", "ERROR"), resp.lines.map { it.severity })
    }

    @Test
    fun `categoryContains is case-insensitive`() {
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] INFO  - ide-introspector-audit - call A
            |2026-05-24 14:30:00,001 [   1] INFO  - com.foo.Bar             - other
            |
            """.trimMargin(),
        )
        val resp = reader.tail(linesRequested = 10, categoryContains = "AUDIT")
        assertEquals(1, resp.lines.size)
        assertEquals("ide-introspector-audit", resp.lines[0].category)
    }

    @Test
    fun `regex catastrophic backtracking against all-a's line times out and skips the match`() {
        val reader = newReader(
            "2026-05-24 14:30:00,000 [   1] INFO  - cat - " + "a".repeat(40) + "\n",
        )
        // (a+)+b classic ReDoS — on a long all-a's tail, naive Pattern.find hangs many seconds.
        // Our 50 ms per-line timeout must defuse it: filter falls back to "no match" so the
        // line is dropped without freezing the IDE.
        val resp = reader.tail(linesRequested = 10, regex = "(a+)+b")
        assertEquals("regex timeout should drop the line, not crash", 0, resp.lines.size)
    }

    @Test
    fun `invalid regex is silently ignored — all lines pass through`() {
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] INFO  - cat - one
            |2026-05-24 14:30:00,001 [   1] INFO  - cat - two
            |
            """.trimMargin(),
        )
        val resp = reader.tail(linesRequested = 10, regex = "[unclosed")
        // Bad pattern compiles to null → no filter applied at all.
        assertEquals(2, resp.lines.size)
    }

    // ====================================================================================
    // Section 3: errors_since — grouping
    // ====================================================================================

    @Test
    fun `groupByThrowable=true collapses ERROR with tab-prefixed continuations`() {
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] ERROR - cat - java.lang.NullPointerException: boom
            |${"\t"}at com.foo.A.a(A.kt:1)
            |${"\t"}at com.foo.B.b(B.kt:2)
            |${"\t"}at com.foo.C.c(C.kt:3)
            |${"\t"}at com.foo.D.d(D.kt:4)
            |${"\t"}at com.foo.E.e(E.kt:5)
            |Caused by: java.io.IOException: disk
            |${"\t"}at com.foo.X.x(X.kt:10)
            |${"\t"}at com.foo.Y.y(Y.kt:11)
            |${"\t"}at com.foo.Z.z(Z.kt:12)
            |2026-05-24 14:30:01,000 [   1] INFO  - cat - life goes on
            |
            """.trimMargin(),
        )
        val resp = reader.errorsSince(lastMinutes = 24 * 60, minSeverity = "WARN", limit = 100, groupByThrowable = true)
        assertEquals("one grouped ERROR entry expected", 1, resp.errors.size)
        val err = resp.errors[0]
        assertEquals("ERROR", err.severity)
        assertEquals("java.lang.NullPointerException", err.throwableClass)
        val stack = err.stacktrace
        assertNotNull("stacktrace must be populated when continuations folded", stack)
        // 5 + 1 + 3 continuation lines, separated by '\n'.
        assertEquals(8 + 1, stack!!.lines().size)
        assertTrue("stack must mention Caused by line", stack.contains("Caused by: java.io.IOException: disk"))
    }

    @Test
    fun `groupByThrowable=false yields one entry per ERROR and drops continuations`() {
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] ERROR - cat - java.lang.NullPointerException: boom
            |${"\t"}at com.foo.A.a(A.kt:1)
            |${"\t"}at com.foo.B.b(B.kt:2)
            |
            """.trimMargin(),
        )
        val resp = reader.errorsSince(lastMinutes = 24 * 60, minSeverity = "WARN", limit = 100, groupByThrowable = false)
        assertEquals(1, resp.errors.size)
        assertNull("stacktrace null when grouping disabled", resp.errors[0].stacktrace)
    }

    @Test
    fun `minSeverity below WARN is rejected`() {
        val reader = newReader("")
        try {
            reader.errorsSince(minSeverity = "INFO")
            org.junit.Assert.fail("INFO should be rejected — use log.tail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("WARN") == true)
        }
    }

    // ====================================================================================
    // Section 4: redaction
    // ====================================================================================

    @Test
    fun `redaction masks Bearer tokens and sets redacted=true`() {
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] INFO  - net - Bearer abc123def456ghi789
            |
            """.trimMargin(),
        )
        val resp = reader.tail(linesRequested = 10)
        assertTrue("redacted flag must be true after a Bearer match", resp.redacted)
        assertTrue(
            "raw must contain the redaction marker",
            resp.lines[0].raw.contains("***REDACTED***"),
        )
        assertFalse(
            "the secret must not survive in raw",
            resp.lines[0].raw.contains("abc123def456ghi789"),
        )
    }

    @Test
    fun `redaction skips short token= values via length floor`() {
        // Plan: `token=([A-Za-z0-9._\-]{16,})` — anything under 16 chars passes through.
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] INFO  - net - url=https://example.com/?token=abc&user=alice
            |
            """.trimMargin(),
        )
        val resp = reader.tail(linesRequested = 10)
        assertFalse("short token must not trip the redactor", resp.redacted)
        assertTrue(resp.lines[0].raw.contains("token=abc"))
    }

    @Test
    fun `redaction masks Authorization header`() {
        val reader = newReader(
            """
            |2026-05-24 14:30:00,000 [   1] INFO  - net - Authorization: Bearer xyzxyzxyzxyz1234
            |
            """.trimMargin(),
        )
        val resp = reader.tail(linesRequested = 10)
        assertTrue(resp.redacted)
        assertFalse(resp.lines[0].raw.contains("xyzxyzxyzxyz1234"))
    }

    // ====================================================================================
    // Section 5: time cutoff
    // ====================================================================================

    @Test
    fun `sinceIsoTimestamp cutoff prunes earlier lines`() {
        val reader = newReader(
            """
            |2026-05-24 14:00:00,000 [   1] ERROR - cat - early java.lang.Foo: x
            |2026-05-24 15:00:00,000 [   1] ERROR - cat - late  java.lang.Bar: y
            |
            """.trimMargin(),
        )
        val resp = reader.errorsSince(
            sinceIsoTimestamp = "2026-05-24T14:30:00",
            minSeverity = "WARN",
        )
        assertEquals(1, resp.errors.size)
        assertTrue("only the late ERROR should survive", resp.errors[0].message!!.contains("late"))
    }

    @Test
    fun `malformed sinceIsoTimestamp falls back to lastMinutes default`() {
        val reader = newReader("")
        // Should not throw — falls back to lastMinutes; with empty content, just empty result.
        val resp = reader.errorsSince(sinceIsoTimestamp = "not-a-date", lastMinutes = 30)
        assertEquals(0, resp.errors.size)
        // `since` echoes the input verbatim — caller sees what they asked for, not a silent rewrite.
        assertEquals("not-a-date", resp.since)
    }

    @Test
    fun `severity unknown is rejected`() {
        val reader = LogReader(tmp.newFile("idea.log").toPath())
        try {
            reader.tail(severity = "SHOUT")
            org.junit.Assert.fail("Unknown severity should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("SHOUT") == true)
        }
    }

    // ====================================================================================
    // Section 6: limit / response cap
    // ====================================================================================

    @Test
    fun `limit caps returned errors and total reflects pre-truncation count`() {
        // Use %02d so seconds remain zero-padded (00..09) for it=1..9 and stay 2-digit (10)
        // for it=10 — otherwise the 10th line emits `14:00:010,000` which fails LOG_LINE.
        val lines = (1..10).joinToString("\n") {
            "2026-05-24 14:00:%02d,000 [   1] ERROR - cat - java.lang.RuntimeException: msg$it".format(it)
        } + "\n"
        val reader = newReader(lines)
        val resp = reader.errorsSince(lastMinutes = 24 * 60, minSeverity = "WARN", limit = 3)
        assertEquals(3, resp.errors.size)
        assertEquals("total must report the unfiltered count", 10, resp.total)
        assertTrue("truncated must be true when total > returned", resp.truncated)
    }
}
