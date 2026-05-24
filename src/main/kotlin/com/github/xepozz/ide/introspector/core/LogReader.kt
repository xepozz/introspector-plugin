package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.ErrorEntry
import com.github.xepozz.ide.introspector.model.LogErrorsSinceResponse
import com.github.xepozz.ide.introspector.model.LogLine
import com.github.xepozz.ide.introspector.model.LogTailResponse
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.concurrent.thread

/**
 * Pure-I/O log reader for the IDE's own `idea.log`. Tails efficiently — for the common
 * "last N lines" case it seeks to the end of the file and reads only the trailing
 * [MAX_TAIL_BYTES] bytes, so a 500 MB log still answers in tens of milliseconds.
 *
 * Constructor takes the log file path (and a rotation supplier) so unit tests can point
 * it at a `TemporaryFolder` fixture; the production `LogToolset` plugs in
 * `PathManager.getLogPath() / "idea.log"`.
 *
 * Threading: no EDT, no ReadAction. Callers (MCP suspend funs, the tool window) invoke
 * directly from a worker thread.
 */
class LogReader(
    /** Path to the live `idea.log`. May not exist on a fresh sandbox install. */
    private val logFile: Path,
    /** Supplier of rotation files in oldest→newest-rotation order (`.5`, `.4`, …, `.1`). */
    private val rotationFiles: () -> List<Path> = { defaultRotations(logFile) },
) {

    /** Path the production code would report to MCP clients, even when no file exists yet. */
    fun logPath(): String = logFile.toString()

    /**
     * Returns the last [linesRequested] parsed/unparsed lines, optionally filtered.
     *
     * Reads at most [MAX_TAIL_BYTES] from the end of the file. If the file is larger,
     * [LogTailResponse.truncated] is `true` and earlier history is silently dropped. We
     * deliberately do NOT chase `idea.log.1` to "fill in" — a fresh rotation legitimately
     * has 2 lines and the caller should know that.
     */
    fun tail(
        linesRequested: Int = 200,
        categoryContains: String? = null,
        severity: String? = null,
        regex: String? = null,
        maxBytes: Int = 65_536,
    ): LogTailResponse {
        val cappedLines = linesRequested.coerceIn(1, MAX_LINES)
        val cappedBytes = maxBytes.coerceIn(MIN_RESPONSE_BYTES, MAX_RESPONSE_BYTES)
        val minSeverityIdx = severity?.let { severityIndex(it) } ?: -1
        val compiledRegex = compileRegexQuietly(regex)

        val tailText = readTail(logFile, MAX_TAIL_BYTES) ?: return LogTailResponse(
            logPath = logFile.toString(),
            lines = emptyList(),
            totalLinesScanned = 0,
            truncated = false,
        )
        val rawLines = tailText.text.split('\n')
        // The first chunk may be a partial line (we cut at an arbitrary byte offset and
        // then advanced to the next newline). Drop it if we landed mid-file.
        val candidateLines = if (tailText.truncated && rawLines.isNotEmpty()) rawLines.drop(1) else rawLines
        // Last entry of split('\n') is "" when text ended with '\n'.
        val cleanLines = candidateLines.dropLastWhile { it.isEmpty() }

        val parsed = cleanLines.map { parseLine(it) }
        val totalScanned = parsed.size

        val redactionState = RedactionState()
        val filtered = parsed.asReversed().asSequence()
            .filter { lineMatchesFilters(it, categoryContains, minSeverityIdx, compiledRegex) }
            .take(cappedLines)
            .toList()
            .asReversed()
            .map { redactLine(it, redactionState) }

        return capLogTailResponseBytes(
            LogTailResponse(
                logPath = logFile.toString(),
                lines = filtered,
                totalLinesScanned = totalScanned,
                truncated = tailText.truncated,
                redacted = redactionState.anyRedacted,
            ),
            cappedBytes,
        )
    }

    /**
     * Returns WARN+ entries since a cutoff. When the cutoff predates the current log file's
     * first line, walks rotation files oldest→newest, up to [MAX_ROTATIONS] / [MAX_ROTATION_BYTES].
     *
     * If [groupByThrowable] is `true`, continuation lines (`\tat …`, `Caused by …`, `\t… more`)
     * are folded into the preceding ERROR's `stacktrace` and `throwableClass` is parsed from
     * a Java-style `foo.Bar.Baz: msg` prefix when present.
     */
    fun errorsSince(
        sinceIsoTimestamp: String? = null,
        lastMinutes: Int? = 30,
        minSeverity: String = "WARN",
        limit: Int = 100,
        groupByThrowable: Boolean = true,
        maxBytes: Int = 131_072,
    ): LogErrorsSinceResponse {
        val cappedLimit = limit.coerceIn(1, MAX_ENTRIES)
        val cappedBytes = maxBytes.coerceIn(MIN_RESPONSE_BYTES, MAX_RESPONSE_BYTES)
        val minIdx = severityIndex(minSeverity).also {
            require(it >= severityIndex("WARN")) {
                "minSeverity must be WARN or ERROR (got '$minSeverity'); use log.tail for INFO/DEBUG/TRACE"
            }
        }
        val cutoff = parseCutoff(sinceIsoTimestamp, lastMinutes)
        val sinceOut = sinceIsoTimestamp ?: ISO.format(cutoff)

        val sources = collectErrorSources()
        if (sources.isEmpty()) {
            return LogErrorsSinceResponse(
                since = sinceOut,
                errors = emptyList(),
                total = 0,
                truncated = false,
            )
        }

        val allLines = mutableListOf<LogLine>()
        var byteBudget = MAX_ROTATION_BYTES
        var truncatedBudget = false
        for (src in sources) {
            if (byteBudget <= 0) {
                truncatedBudget = true
                break
            }
            val text = readUpTo(src, byteBudget) ?: continue
            byteBudget -= text.bytesRead
            if (text.truncated) truncatedBudget = true
            for (line in text.text.split('\n')) {
                if (line.isEmpty()) continue
                allLines += parseLine(line)
            }
        }

        val grouped = if (groupByThrowable) groupThrowables(allLines, minIdx) else linesAsEntries(allLines, minIdx)
        val sinceCutoff = grouped.filter { entry ->
            val ts = entry.timestamp?.let { tryParseLogTimestamp(it) }
            ts == null || !ts.isBefore(cutoff)
        }

        val total = sinceCutoff.size
        val redactionState = RedactionState()
        val capped = sinceCutoff.take(cappedLimit).map { redactErrorEntry(it, redactionState) }
        val truncated = truncatedBudget || total > capped.size

        return capLogErrorsResponseBytes(
            LogErrorsSinceResponse(
                since = sinceOut,
                errors = capped,
                total = total,
                truncated = truncated,
                redacted = redactionState.anyRedacted,
            ),
            cappedBytes,
        )
    }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private fun collectErrorSources(): List<Path> {
        val main = if (Files.isRegularFile(logFile)) listOf(logFile) else emptyList()
        val rotations = runCatching { rotationFiles().take(MAX_ROTATIONS) }.getOrElse { emptyList() }
        // Oldest first so the resulting List<LogLine> is naturally chronological.
        return rotations.reversed() + main
    }

    private data class TailRead(val text: String, val truncated: Boolean, val bytesRead: Int)

    /**
     * Reads the trailing [maxBytes] of [file] as UTF-8. Returns `null` if the file is missing
     * or unreadable. When the file is larger than [maxBytes], the returned text starts at the
     * first `\n` strictly inside the buffer to avoid splitting a multi-byte code point or a
     * line; [TailRead.truncated] is then `true`.
     */
    private fun readTail(file: Path, maxBytes: Int): TailRead? {
        if (!Files.isRegularFile(file)) return null
        return try {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val len = raf.length()
                val read = minOf(len, maxBytes.toLong()).toInt()
                val truncated = len > maxBytes
                val start = (len - read).coerceAtLeast(0L)
                raf.seek(start)
                val buf = ByteArray(read)
                var off = 0
                while (off < read) {
                    val n = raf.read(buf, off, read - off)
                    if (n < 0) break
                    off += n
                }
                val actualBytes = if (off == read) buf else buf.copyOf(off)
                TailRead(String(actualBytes, StandardCharsets.UTF_8), truncated, off)
            }
        } catch (_: AccessDeniedException) {
            TailRead("<permission denied: $file>", false, 0)
        } catch (_: NoSuchFileException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /** Read whole-file (up to [maxBytes]) for rotation walking — we always read from offset 0. */
    private fun readUpTo(file: Path, maxBytes: Int): TailRead? {
        if (!Files.isRegularFile(file)) return null
        return try {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val len = raf.length()
                val read = minOf(len, maxBytes.toLong()).toInt()
                val truncated = len > maxBytes
                val buf = ByteArray(read)
                var off = 0
                while (off < read) {
                    val n = raf.read(buf, off, read - off)
                    if (n < 0) break
                    off += n
                }
                val actualBytes = if (off == read) buf else buf.copyOf(off)
                TailRead(String(actualBytes, StandardCharsets.UTF_8), truncated, off)
            }
        } catch (_: AccessDeniedException) {
            TailRead("<permission denied: $file>", false, 0)
        } catch (_: NoSuchFileException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun lineMatchesFilters(
        line: LogLine,
        categoryContains: String?,
        minSeverityIdx: Int,
        regex: Pattern?,
    ): Boolean {
        if (minSeverityIdx >= 0) {
            val s = line.severity ?: return false
            if (severityIndex(s) < minSeverityIdx) return false
        }
        if (categoryContains != null) {
            val c = line.category ?: return false
            if (!c.contains(categoryContains, ignoreCase = true)) return false
        }
        if (regex != null) {
            if (!regexFindWithTimeout(regex, line.raw)) return false
        }
        return true
    }

    // ------------------------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------------------------

    /** Parse a single line into [LogLine]. Non-matching lines become `parsed=false`. */
    fun parseLine(raw: String): LogLine {
        val m = LOG_LINE.matcher(raw)
        if (m.matches()) {
            return LogLine(
                timestamp = m.group(1),
                thread = m.group(2),
                severity = m.group(3),
                category = m.group(4),
                message = m.group(5),
                raw = raw,
                parsed = true,
            )
        }
        return LogLine(null, null, null, null, null, raw, parsed = false)
    }

    private fun linesAsEntries(lines: List<LogLine>, minIdx: Int): List<ErrorEntry> {
        val out = mutableListOf<ErrorEntry>()
        for (line in lines) {
            val sev = line.severity ?: continue
            if (severityIndex(sev) < minIdx) continue
            out += ErrorEntry(
                timestamp = line.timestamp,
                thread = line.thread,
                severity = sev,
                category = line.category,
                message = line.message,
                stacktrace = null,
                throwableClass = throwableClassOf(line.message),
                raw = line.raw,
            )
        }
        return out
    }

    private fun groupThrowables(lines: List<LogLine>, minIdx: Int): List<ErrorEntry> {
        val out = mutableListOf<ErrorEntry>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val sev = line.severity
            if (sev != null && severityIndex(sev) >= minIdx) {
                val rawBuf = StringBuilder(line.raw)
                val stackBuf = StringBuilder()
                var j = i + 1
                while (j < lines.size && isContinuation(lines[j])) {
                    rawBuf.append('\n').append(lines[j].raw)
                    if (stackBuf.isNotEmpty()) stackBuf.append('\n')
                    stackBuf.append(lines[j].raw)
                    j++
                }
                out += ErrorEntry(
                    timestamp = line.timestamp,
                    thread = line.thread,
                    severity = sev,
                    category = line.category,
                    message = line.message,
                    stacktrace = if (stackBuf.isEmpty()) null else stackBuf.toString(),
                    throwableClass = throwableClassOf(line.message),
                    raw = rawBuf.toString(),
                )
                i = j
            } else {
                i++
            }
        }
        return out
    }

    private fun isContinuation(line: LogLine): Boolean {
        if (line.parsed) return false
        val s = line.raw
        if (s.isEmpty()) return false
        if (s.startsWith("\t") || s.startsWith(" ")) return true
        if (s.startsWith("Caused by:")) return true
        if (s.startsWith("Suppressed:")) return true
        return false
    }

    private fun throwableClassOf(message: String?): String? {
        if (message == null) return null
        // 'foo.Bar.Baz: msg' — class FQN before the first ': '.
        val colon = message.indexOf(": ")
        val candidate = if (colon > 0) message.substring(0, colon) else message
        if (!candidate.matches(THROWABLE_FQN)) return null
        return candidate
    }

    private fun parseCutoff(sinceIsoTimestamp: String?, lastMinutes: Int?): LocalDateTime {
        if (sinceIsoTimestamp != null) {
            for (fmt in ISO_PARSERS) {
                val parsed = runCatching { LocalDateTime.parse(sinceIsoTimestamp, fmt) }.getOrNull()
                if (parsed != null) return parsed
            }
            // Offset format: 2026-05-24T14:00:00+02:00 — convert to local datetime via OffsetDateTime
            val withOffset = runCatching {
                java.time.OffsetDateTime.parse(sinceIsoTimestamp).toLocalDateTime()
            }.getOrNull()
            if (withOffset != null) return withOffset
            // Fall through to lastMinutes / default.
        }
        val minutes = (lastMinutes ?: 30).coerceIn(1, MAX_LAST_MINUTES)
        return LocalDateTime.now().minusMinutes(minutes.toLong())
    }

    // ------------------------------------------------------------------------------------
    // Regex with per-line timeout — guards against catastrophic backtracking.
    // ------------------------------------------------------------------------------------

    /**
     * `Pattern.matcher(s).find()` is uninterruptible on a regular thread, so a catastrophic
     * pattern like `(a+)+b` against an all-a's line can hang for minutes. We run the find in
     * a daemon thread, wait [REGEX_LINE_TIMEOUT_MS] ms, and on timeout return `false` (non-match)
     * — better to silently drop one suspect filter than to lock up the IDE.
     */
    private fun regexFindWithTimeout(pattern: Pattern, input: String): Boolean {
        val result = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val matcher = pattern.matcher(input)
        val t = thread(start = true, isDaemon = true, name = "log-regex") {
            result.set(runCatching { matcher.find() }.getOrElse { false })
        }
        t.join(REGEX_LINE_TIMEOUT_MS)
        if (t.isAlive) {
            // Best-effort cancel — Matcher exposes `interrupt()` only via `Thread.interrupt()`.
            t.interrupt()
            return false
        }
        return result.get() == true
    }

    private fun compileRegexQuietly(regex: String?): Pattern? {
        if (regex.isNullOrEmpty()) return null
        return try {
            Pattern.compile(regex)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    // ------------------------------------------------------------------------------------
    // Secret redaction. On by default per plan — token leakage cost ≫ false positives.
    // ------------------------------------------------------------------------------------

    private class RedactionState(var anyRedacted: Boolean = false)

    private fun redact(s: String?, state: RedactionState): String? {
        if (s.isNullOrEmpty()) return s
        var out = s
        var changed = false
        for ((pat, repl) in REDACTION_PATTERNS) {
            val m = pat.matcher(out)
            if (m.find()) {
                out = m.replaceAll(repl)
                changed = true
            }
        }
        if (changed) state.anyRedacted = true
        return out
    }

    private fun redactLine(line: LogLine, state: RedactionState): LogLine {
        val newRaw = redact(line.raw, state) ?: line.raw
        val newMsg = if (line.message == null) null else redact(line.message, state)
        return line.copy(raw = newRaw, message = newMsg)
    }

    private fun redactErrorEntry(entry: ErrorEntry, state: RedactionState): ErrorEntry {
        val newRaw = redact(entry.raw, state) ?: entry.raw
        val newMsg = if (entry.message == null) null else redact(entry.message, state)
        val newStack = if (entry.stacktrace == null) null else redact(entry.stacktrace, state)
        return entry.copy(raw = newRaw, message = newMsg, stacktrace = newStack)
    }

    // ------------------------------------------------------------------------------------
    // Response byte-cap (cheap last-pass shrink, line-aligned).
    // ------------------------------------------------------------------------------------

    private fun capLogTailResponseBytes(resp: LogTailResponse, maxBytes: Int): LogTailResponse {
        var bytes = 0
        val kept = mutableListOf<LogLine>()
        for (line in resp.lines) {
            val lineBytes = line.raw.toByteArray(StandardCharsets.UTF_8).size
            if (bytes + lineBytes > maxBytes && kept.isNotEmpty()) {
                return resp.copy(lines = kept, truncated = true)
            }
            bytes += lineBytes
            kept += line
        }
        return resp.copy(lines = kept)
    }

    private fun capLogErrorsResponseBytes(resp: LogErrorsSinceResponse, maxBytes: Int): LogErrorsSinceResponse {
        var bytes = 0
        val kept = mutableListOf<ErrorEntry>()
        for (entry in resp.errors) {
            val entryBytes = entry.raw.toByteArray(StandardCharsets.UTF_8).size
            if (bytes + entryBytes > maxBytes && kept.isNotEmpty()) {
                return resp.copy(errors = kept, truncated = true)
            }
            bytes += entryBytes
            kept += entry
        }
        return resp.copy(errors = kept)
    }

    companion object {
        const val MAX_TAIL_BYTES = 1_048_576           // 1 MB
        const val MAX_LINES = 5_000
        const val MAX_ENTRIES = 1_000
        const val MAX_LAST_MINUTES = 1_440             // 24h
        const val MIN_RESPONSE_BYTES = 1_024
        const val MAX_RESPONSE_BYTES = 524_288         // 512 KB
        const val MAX_ROTATIONS = 5
        const val MAX_ROTATION_BYTES = 8 * 1_048_576   // 8 MB cumulative
        const val REGEX_LINE_TIMEOUT_MS = 50L

        /**
         * Fixed IDEA log4j2 layout (see `application/logger.xml` in intellij-community):
         *   yyyy-MM-dd HH:mm:ss,SSS [thread] SEVERITY - category - message
         * `thread` is right-padded so we allow optional leading spaces.
         */
        private val LOG_LINE: Pattern = Pattern.compile(
            """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3})\s+\[\s*(\d+)\s*]\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+-\s+(\S+)\s+-\s+(.*)$"""
        )

        private val SEVERITIES = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")

        private val THROWABLE_FQN = Regex("""[A-Za-z_$][\w$.]*\.[A-Z][\w$]*""")

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        private val ISO_PARSERS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"),
        )

        private val LOG_TS_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")

        private val REDACTION_PATTERNS: List<Pair<Pattern, String>> = listOf(
            // Bearer tokens — keep prefix, mask the rest.
            Pattern.compile("""Bearer\s+[A-Za-z0-9._\-]{8,}""") to "Bearer ***REDACTED***",
            // Authorization headers — anywhere a header line shows up.
            Pattern.compile("""(?i)authorization:\s*\S+""") to "Authorization: ***REDACTED***",
            // token=… in URLs / config dumps — length floor to avoid masking `token=abc`.
            Pattern.compile("""(?i)token=([A-Za-z0-9._\-]{16,})""") to "token=***REDACTED***",
            // JetBrains JBA cookie/header — used by their own MCP server too.
            Pattern.compile("""(?i)jba_auth=\S+""") to "jba_auth=***REDACTED***",
        )

        fun severityIndex(s: String): Int {
            val idx = SEVERITIES.indexOf(s.uppercase())
            require(idx >= 0) { "Unknown severity '$s'; expected one of $SEVERITIES" }
            return idx
        }

        fun tryParseLogTimestamp(ts: String): LocalDateTime? =
            runCatching { LocalDateTime.parse(ts, LOG_TS_FORMATTER) }.getOrNull()

        /**
         * Default rotation list for a given log path. The IDE writes `idea.log.1`, `idea.log.2`, …
         * — we return them in `.1, .2, .3, …` order (newest-first rotation index, i.e. the .1 file
         * holds entries immediately preceding the current log). Caller decides traversal order.
         */
        fun defaultRotations(logFile: Path): List<Path> {
            val parent = logFile.parent ?: return emptyList()
            val baseName = logFile.fileName.toString()
            return (1..MAX_ROTATIONS).map { parent.resolve("$baseName.$it") }.filter { Files.isRegularFile(it) }
        }
    }
}
