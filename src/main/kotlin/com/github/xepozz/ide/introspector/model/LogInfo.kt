package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * One parsed (or unparseable) line of `idea.log`.
 *
 * `parsed=true` means the standard IDEA log4j2 layout matched
 * (`yyyy-MM-dd HH:mm:ss,SSS [thread] SEVERITY - category - message`) and every field
 * carries data. `parsed=false` means the line did not match — typically a stacktrace
 * continuation (`\tat …`, `Caused by …`, `\t… N more`) or a raw `println` from some
 * misbehaving logger. In that case only `raw` is meaningful; severity / category /
 * message filters skip these, but regex filters still see the `raw` text.
 */
@Serializable
data class LogLine(
    val timestamp: String?,
    val thread: String?,
    val severity: String?,
    val category: String?,
    val message: String?,
    val raw: String,
    val parsed: Boolean = true,
)

@Serializable
data class LogTailResponse(
    val logPath: String,
    val lines: List<LogLine>,
    val totalLinesScanned: Int,
    val truncated: Boolean,
    val redacted: Boolean = false,
)

/**
 * One WARN+/ERROR entry from `idea.log`.
 *
 * When `groupByThrowable=true` (default), Java stacktrace continuation lines are folded
 * into `stacktrace` and `throwableClass` is parsed from a `foo.Bar.Baz: …` prefix on the
 * message when present. `raw` is the joined original lines (so the caller can grep the
 * exact bytes back if needed).
 */
@Serializable
data class ErrorEntry(
    val timestamp: String?,
    val thread: String?,
    val severity: String,
    val category: String?,
    val message: String?,
    val stacktrace: String? = null,
    val throwableClass: String? = null,
    val raw: String,
)

@Serializable
data class LogErrorsSinceResponse(
    val since: String,
    val errors: List<ErrorEntry>,
    val total: Int,
    val truncated: Boolean,
    val redacted: Boolean = false,
)
