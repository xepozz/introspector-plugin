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

        // When a severity filter is set, a multi-line stacktrace under an ERROR has continuation
        // lines (parsed=false, no severity prefix) that would be silently dropped by the filter.
        // The tool description promises continuations come through as `parsed=false` entries; the
        // user-visible value is the stack frames under their matched header. Mark, in forward
        // order, every continuation that follows a header passing the severity filter so the
        // downstream reverse-walk keeps it alongside the header.
        val keepIndices = computeKeepIndices(parsed, categoryContains, minSeverityIdx, compiledRegex)

        val redactionState = RedactionState()
        val filtered = parsed.withIndex().toList().asReversed().asSequence()
            .filter { it.index in keepIndices }
            .take(cappedLines)
            .toList()
            .asReversed()
            .map { redactLine(it.value, redactionState) }

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

        // Reverse-seek the current log first — on a 60 MB idea.log a `lastMinutes=5` query
        // hits recent entries in <50 ms by reading only the trailing 1 MB. We then walk
        // rotations newest-first ONLY if the tail's oldest parsed timestamp is still after
        // the cutoff (i.e. the cutoff predates what we've seen). Forward-from-byte-0 reads
        // would parse day-1 history and never reach recent entries before hitting the 8 MB
        // cap — see Finding 1 in docs/reviews/log-group.md.
        val allLines = mutableListOf<LogLine>()
        var byteBudget = MAX_ROTATION_BYTES
        var truncatedBudget = false

        val mainExists = Files.isRegularFile(logFile)
        val mainTail = if (mainExists) readTail(logFile, MAX_TAIL_BYTES) else null
        if (mainTail != null) {
            byteBudget -= mainTail.bytesRead
            if (mainTail.truncated) truncatedBudget = true
            val rawSplit = mainTail.text.split('\n')
            // Drop the (possibly partial) first chunk only when we landed mid-file.
            val candidate = if (mainTail.truncated && rawSplit.isNotEmpty()) rawSplit.drop(1) else rawSplit
            for (raw in candidate) {
                if (raw.isEmpty()) continue
                allLines += parseLine(raw)
            }
        }

        if (shouldWalkRotations(allLines, cutoff, mainTail?.truncated == true)) {
            // Read rotations newest-first (`.1`, `.2`, …) and PREPEND each batch so the final
            // list stays chronological (oldest first).
            val rotations = runCatching { rotationFiles().take(MAX_ROTATIONS) }.getOrElse { emptyList() }
            for (rot in rotations) {
                if (byteBudget <= 0) { truncatedBudget = true; break }
                val rotTail = readTail(rot, minOf(byteBudget, MAX_TAIL_BYTES)) ?: continue
                byteBudget -= rotTail.bytesRead
                if (rotTail.truncated) truncatedBudget = true
                val rawSplit = rotTail.text.split('\n')
                val candidate = if (rotTail.truncated && rawSplit.isNotEmpty()) rawSplit.drop(1) else rawSplit
                val prepended = ArrayList<LogLine>(candidate.size)
                for (raw in candidate) {
                    if (raw.isEmpty()) continue
                    prepended += parseLine(raw)
                }
                allLines.addAll(0, prepended)
                // If this rotation's earliest parsed line is still after the cutoff, keep walking.
                if (!shouldWalkRotations(allLines, cutoff, rotTail.truncated)) break
            }
        }

        if (allLines.isEmpty() && !mainExists) {
            return LogErrorsSinceResponse(
                since = sinceOut,
                errors = emptyList(),
                total = 0,
                truncated = false,
            )
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

    /**
     * Walk rotations only when the data we have doesn't yet cover [cutoff] — i.e. when the
     * earliest parsed timestamp in [lines] is still strictly after the cutoff. Empty/parse-
     * less data means walk (we don't know what we have). Once we've seen a timestamp ≤
     * cutoff we know rotations contain only older entries the caller doesn't want.
     *
     * Note: if the most recent file read was itself truncated (file > 1 MB tail), older
     * entries from the SAME file are silently dropped — that's reflected in
     * `truncatedBudget=true` at the caller, not by chasing rotations.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun shouldWalkRotations(lines: List<LogLine>, cutoff: LocalDateTime, lastReadTruncated: Boolean): Boolean {
        val earliest = lines.asSequence()
            .mapNotNull { it.timestamp?.let(::tryParseLogTimestamp) }
            .firstOrNull() ?: return true
        return earliest.isAfter(cutoff)
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

    /**
     * Walks [parsed] forward and returns the set of indices that should appear in the response:
     * any line that matches the filters, plus the stacktrace-continuation lines (`\tat …`,
     * `Caused by …`, indented continuations) that immediately follow it. Continuations alone
     * never pass the severity filter — they have no severity prefix — so without this sticky
     * pass the user sees `ERROR foo.Bar: boom` with zero stack frames.
     */
    private fun computeKeepIndices(
        parsed: List<LogLine>,
        categoryContains: String?,
        minSeverityIdx: Int,
        regex: Pattern?,
    ): Set<Int> {
        val keep = HashSet<Int>(parsed.size)
        var i = 0
        while (i < parsed.size) {
            if (lineMatchesFilters(parsed[i], categoryContains, minSeverityIdx, regex)) {
                keep += i
                // Sticky-attach following continuations so multi-line stacktraces survive the
                // severity filter. Stop at the next parsed (timestamped) line.
                var j = i + 1
                while (j < parsed.size && isContinuation(parsed[j])) {
                    keep += j
                    j++
                }
                i = j
            } else {
                i++
            }
        }
        return keep
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
            val throwable = throwableClassOf(line.message)
            out += ErrorEntry(
                timestamp = line.timestamp,
                thread = line.thread,
                severity = sev,
                category = line.category,
                message = stripThrowablePrefix(line.message, throwable),
                stacktrace = null,
                throwableClass = throwable,
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
                val throwable = throwableClassOf(line.message)
                out += ErrorEntry(
                    timestamp = line.timestamp,
                    thread = line.thread,
                    severity = sev,
                    category = line.category,
                    message = stripThrowablePrefix(line.message, throwable),
                    stacktrace = if (stackBuf.isEmpty()) null else stackBuf.toString(),
                    throwableClass = throwable,
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

    /**
     * When a log line has the shape `foo.Bar.Baz: actual message`, [throwableClassOf]
     * lifts the FQN out into `throwableClass`. Strip it from the `message` field too so
     * callers don't have to re-parse it (see `docs/plans/log-group.md`).
     */
    private fun stripThrowablePrefix(message: String?, throwable: String?): String? {
        if (message == null || throwable == null) return message
        val prefix = "$throwable: "
        return if (message.startsWith(prefix)) message.substring(prefix.length) else message
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
     * pattern like `(a+)+b` against an all-a's line can hang for minutes. `Thread.interrupt()`
     * doesn't help — `Matcher` never checks the flag. We wrap the input in
     * [InterruptibleCharSequence] which throws after [REGEX_LINE_TIMEOUT_MS] ms; the matcher
     * reads characters in its hot loop, so the throw escapes within microseconds. No threads,
     * no leak. Pattern after https://www.ocpsoft.org/regex/how-to-interrupt-a-long-running-infinite-java-regular-expression/.
     */
    private fun regexFindWithTimeout(pattern: Pattern, input: String): Boolean {
        val wrapped = InterruptibleCharSequence(input, System.nanoTime() + REGEX_LINE_TIMEOUT_MS * 1_000_000L)
        val matcher = pattern.matcher(wrapped)
        return try {
            matcher.find()
        } catch (_: RegexTimeoutException) {
            false
        }
    }

    private class RegexTimeoutException : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this // avoid stack-fill cost on the hot path
    }

    /**
     * `CharSequence` that throws [RegexTimeoutException] from `charAt()` once the deadline
     * has passed. `Matcher` reads characters via `charAt()` in its inner loop, so a runaway
     * `find()` aborts within microseconds of the deadline — no extra threads, no CPU leak.
     */
    private class InterruptibleCharSequence(
        private val inner: CharSequence,
        private val deadlineNanos: Long,
    ) : CharSequence {
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            // Cheap check — `System.nanoTime()` is ~25 ns on modern JVMs.
            if (System.nanoTime() > deadlineNanos) throw RegexTimeoutException()
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            InterruptibleCharSequence(inner.subSequence(startIndex, endIndex), deadlineNanos)
        override fun toString(): String = inner.toString()
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
