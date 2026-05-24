package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.LogReader
import com.github.xepozz.ide.introspector.model.LogErrorsSinceResponse
import com.github.xepozz.ide.introspector.model.LogTailResponse
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Paths

/**
 * `log.*` — read the running IDE's own `idea.log`. Always-available (no optional dependency)
 * — registered in `META-INF/mcp-integration.xml`.
 *
 * Threading: pure file I/O. No EDT, no ReadAction. The 10 s timeout is enforced via
 * [withTimeoutOrNull]; inner work (1 MB tail buffer) usually completes in tens of ms.
 */
class LogToolset : McpToolset {

    @McpTool(name = "log.tail")
    @McpDescription(
        """
        |Returns the last N lines of the IDE's idea.log, optionally filtered by
        |severity, category substring, or a Java regex. Tails efficiently — reads only
        |the last ~1 MB from the end, even when idea.log is hundreds of MB. Never
        |instantiates services, never touches PSI, no EDT — pure I/O.
        |
        |Use this when: debugging plugin behavior, reading the ide-introspector-audit
        |entries written by exec.execute_kotlin_in_ide, watching recent output after an
        |action, hunting an exception by category, or grepping for a message.
        |
        |Do NOT use this when: you only want errors/warnings since a time (use
        |log.errors_since — it groups stacktraces), or you want logs from a different
        |IDE/user — this reads only PathManager.getLogPath()/idea.log.
        |
        |Returns: { logPath, lines: LogLine[], totalLinesScanned, truncated, redacted }
        |where LogLine = { timestamp ('yyyy-MM-dd HH:mm:ss,SSS'), thread, severity,
        |category, message, raw, parsed }. Stacktrace continuation lines are separate
        |LogLine entries with parsed=false; for grouping use log.errors_since.
        |`redacted=true` if a secret pattern was masked.
        |
        |Rotation: only current idea.log is read; older idea.log.1 etc. are ignored
        |even when the current file has fewer than `lines` lines after fresh rotation.
        |
        |Examples:
        |  lines=200                                  — last 200 lines, no filter
        |  lines=500, severity="WARN"                 — last 500 WARN-or-worse
        |  categoryContains="ide-introspector-audit"  — our own audit trail
        |  regex="ClassNotFound|NoSuchMethod"         — linkage failures
        """
    )
    suspend fun log_tail(
        @McpDescription("Number of lines to return from the end of idea.log. Default 200, hard max 5000.")
        lines: Int = 200,
        @McpDescription("Case-insensitive substring filter on the log category. Null = no filter.")
        categoryContains: String? = null,
        @McpDescription("Minimum severity to include: TRACE|DEBUG|INFO|WARN|ERROR. Null = include all.")
        severity: String? = null,
        @McpDescription("Java regex matched against the raw line via find(). Per-line 50 ms timeout — catastrophic patterns skip the filter.")
        regex: String? = null,
        @McpDescription("Response cap in UTF-8 bytes. Default 65536, hard max 524288.")
        maxBytes: Int = 65_536,
    ): LogTailResponse {
        val reader = LogReader(ideaLogPath())
        return withTimeoutOrNull(TOOL_TIMEOUT_MS) {
            reader.tail(
                linesRequested = lines,
                categoryContains = categoryContains,
                severity = severity,
                regex = regex,
                maxBytes = maxBytes,
            )
        } ?: LogTailResponse(
            logPath = reader.logPath(),
            lines = emptyList(),
            totalLinesScanned = 0,
            truncated = true,
        )
    }

    @McpTool(name = "log.errors_since")
    @McpDescription(
        """
        |Returns WARN+ / ERROR entries from idea.log since a timestamp (or last N
        |minutes). Stripped-down, error-focused: groups Java stacktrace continuations
        |('\tat …', 'Caused by …', '\t… more') into the preceding ERROR so each
        |exception is one ErrorEntry with a full stacktrace string.
        |
        |Use this when: 'what failed since I clicked Build?', 'any errors in the last 5
        |minutes?', triaging a flaky session. Faster than log.tail+filter for 'show me
        |errors' because it caps to WARN+ at parse time and short-circuits on time.
        |
        |Do NOT use this when: you want raw last-N lines (log.tail), or need
        |DEBUG/TRACE entries (also log.tail).
        |
        |Returns: { since, errors: ErrorEntry[], total, truncated, redacted } where
        |ErrorEntry = { timestamp, thread, severity, category, message, stacktrace
        |(present when groupByThrowable=true and continuations attached, else null),
        |throwableClass (parsed from 'foo.Bar.Baz: …' prefix, else null), raw (joined
        |original lines) }. `redacted=true` if a secret pattern was masked.
        |
        |Time parsing: 'yyyy-MM-ddTHH:mm:ss' in JVM default zone, optional offset/'Z'.
        |Log timestamps carry no zone — comparison is in JVM zone.
        |
        |Rotation: walks idea.log + idea.log.1, .2 … as needed (cap 5 rotations, 8 MB
        |cumulative budget) when the cutoff predates the current log's first line.
        |
        |Examples:
        |  lastMinutes=10                            — errors in last 10 min
        |  sinceIsoTimestamp="2026-05-24T14:00:00"   — since 2 pm today
        |  minSeverity="ERROR", limit=20             — only true errors, top 20
        |  groupByThrowable=false                    — don't collapse stacktraces
        """
    )
    suspend fun log_errors_since(
        @McpDescription("ISO-8601 timestamp ('2026-05-24T14:30:00' or with offset). Null = use lastMinutes.")
        sinceIsoTimestamp: String? = null,
        @McpDescription("Window in minutes when sinceIsoTimestamp is null. Default 30, hard max 1440.")
        lastMinutes: Int? = 30,
        @McpDescription("Minimum severity: WARN|ERROR. Default WARN. INFO/DEBUG/TRACE rejected — use log.tail.")
        minSeverity: String = "WARN",
        @McpDescription("Cap on returned entries. Default 100, hard max 1000.")
        limit: Int = 100,
        @McpDescription("Collapse '\\tat …', 'Caused by …', '\\t… N more' continuations into the preceding ERROR's stacktrace. Default true.")
        groupByThrowable: Boolean = true,
        @McpDescription("Response cap in UTF-8 bytes. Default 131072, hard max 524288.")
        maxBytes: Int = 131_072,
    ): LogErrorsSinceResponse {
        val reader = LogReader(ideaLogPath())
        return withTimeoutOrNull(TOOL_TIMEOUT_MS) {
            reader.errorsSince(
                sinceIsoTimestamp = sinceIsoTimestamp,
                lastMinutes = lastMinutes,
                minSeverity = minSeverity,
                limit = limit,
                groupByThrowable = groupByThrowable,
                maxBytes = maxBytes,
            )
        } ?: LogErrorsSinceResponse(
            since = sinceIsoTimestamp ?: "",
            errors = emptyList(),
            total = 0,
            truncated = true,
        )
    }

    private fun ideaLogPath() = Paths.get(PathManager.getLogPath(), "idea.log")

    companion object {
        /** Hard 10 s cap per CLAUDE.md timeout rule. Inner I/O is typically <50 ms. */
        private const val TOOL_TIMEOUT_MS = 10_000L
    }
}
