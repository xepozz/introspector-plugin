# `log.*` group — `log.tail` + `log.errors_since`

## Purpose & motivation

New tool group exposing the running IDE's own `idea.log` to MCP clients.
JetBrains' built-in MCP server has **zero** log-access tools — pure-niche gap.
Pays off twice: (1) plugin developers — `exec.*` writes its audit trail to
`idea.log` under category `ide-introspector-audit`; without `log.tail` you can't
read your own audit log via MCP. (2) General users — "why did indexing fail?",
"show errors since I clicked Run".

**Success criterion**: in a single MCP call, an agent can (a) read the last N
log lines with severity/category/regex filters, and (b) pull WARN+ entries
since a timestamp with multi-line stacktraces grouped into one `ErrorEntry`.

## Tool specifications

### `log.tail`

```kotlin
@McpTool(name = "log.tail")
@McpDescription("""…verbatim below…""")
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
): LogTailResponse
```

`@McpDescription` (verbatim, trim-margin):

```
|Returns the last N lines of the IDE's idea.log, optionally filtered by severity,
|category substring, or a Java regex on the raw line. Tails efficiently — reads
|only the last ~1 MB of the file from the end, even when idea.log is hundreds of
|MB. Never instantiates services, never touches PSI, no EDT bouncing — pure I/O.
|
|Use this when: debugging plugin behavior, reading the `ide-introspector-audit`
|entries written by exec.execute_kotlin_in_ide, watching recent output after an
|action, hunting an exception by category, or grepping for a message. Pair with
|log.errors_since when you only care about WARN+ since a time.
|
|Do NOT use this when: you want only errors/warnings since a time (use
|log.errors_since — it groups stacktraces), or you want logs from a different
|IDE / different user — this reads only PathManager.getLogPath()/idea.log.
|
|Returns: { logPath, lines: LogLine[], totalLinesScanned, truncated, redacted }
|where LogLine = { timestamp ('yyyy-MM-dd HH:mm:ss,SSS' as IDEA prints it),
|thread, severity, category, message, raw, parsed }. Stacktrace continuation
|lines are separate LogLine entries with parsed=false; for grouping use
|log.errors_since. `redacted=true` if a secret pattern (Bearer/token/auth) was
|masked.
|
|Rotation: only the current idea.log is read; older idea.log.1 etc. are ignored
|even when the current file has fewer than `lines` lines after a fresh rotation.
|
|Examples:
|  lines=200                                         — last 200 lines, no filter
|  lines=500, severity="WARN"                        — last 500 WARN-or-worse
|  categoryContains="ide-introspector-audit"         — our own audit trail
|  regex="ClassNotFound|NoSuchMethod"                — linkage failures
```

### `log.errors_since`

```kotlin
@McpTool(name = "log.errors_since")
@McpDescription("""…verbatim below…""")
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
): LogErrorsSinceResponse
```

`@McpDescription` (verbatim, trim-margin):

```
|Returns WARN+ / ERROR entries from idea.log since a timestamp (or last N
|minutes). Stripped-down, error-focused: groups Java stacktrace continuations
|('\tat …', 'Caused by …', '\t… more') into the preceding ERROR so each
|exception is one ErrorEntry with a full stacktrace string.
|
|Use this when: 'what failed since I clicked Build?', 'any errors in the last 5
|minutes?', triaging a flaky session. Faster than log.tail+filter for the common
|'show me the errors' question because it caps to WARN+ at parse time and
|short-circuits on the time floor.
|
|Do NOT use this when: you want raw last-N lines (log.tail), or you need
|DEBUG/TRACE entries (also log.tail).
|
|Returns: { since, errors: ErrorEntry[], total, truncated, redacted } where
|ErrorEntry = { timestamp, thread, severity, category, message, stacktrace
|(present when groupByThrowable=true and continuations were attached, else
|null), throwableClass (parsed from 'foo.Bar.Baz: …' prefix, else null), raw
|(joined original lines) }. `redacted=true` if a secret pattern was masked.
|
|Time parsing: 'yyyy-MM-ddTHH:mm:ss' in the JVM default zone, optionally with
|offset or 'Z'. Log timestamps have no zone — comparison is in JVM zone.
|
|Rotation: walks idea.log + idea.log.1, .2 … as needed (cap 5 rotations, 8 MB
|cumulative budget) when the cutoff predates the current log's first line.
|
|Examples:
|  lastMinutes=10                                    — errors in last 10 min
|  sinceIsoTimestamp="2026-05-24T14:00:00"           — since 2 pm today
|  minSeverity="ERROR", limit=20                     — only true errors, top 20
|  groupByThrowable=false                            — don't collapse stacktraces
```

## Args + response models

`model/args/LogArgs.kt`:

```kotlin
@Serializable data class LogTailArgs(
    val lines: Int = 200, val categoryContains: String? = null,
    val severity: String? = null, val regex: String? = null,
    val maxBytes: Int = 65_536,
)
@Serializable data class LogErrorsSinceArgs(
    val sinceIsoTimestamp: String? = null, val lastMinutes: Int? = 30,
    val minSeverity: String = "WARN", val limit: Int = 100,
    val groupByThrowable: Boolean = true, val maxBytes: Int = 131_072,
)
```

`model/LogInfo.kt`:

```kotlin
@Serializable data class LogLine(
    val timestamp: String?, val thread: String?, val severity: String?,
    val category: String?, val message: String?, val raw: String,
    val parsed: Boolean = true,
)
@Serializable data class LogTailResponse(
    val logPath: String, val lines: List<LogLine>,
    val totalLinesScanned: Int, val truncated: Boolean,
    val redacted: Boolean = false,
)
@Serializable data class ErrorEntry(
    val timestamp: String?, val thread: String?, val severity: String,
    val category: String?, val message: String?,
    val stacktrace: String? = null, val throwableClass: String? = null,
    val raw: String,
)
@Serializable data class LogErrorsSinceResponse(
    val since: String, val errors: List<ErrorEntry>,
    val total: Int, val truncated: Boolean, val redacted: Boolean = false,
)
```

## IntelliJ APIs used

- `com.intellij.openapi.application.PathManager.getLogPath(): String` — returns
  the **directory** containing `idea.log` (NOT the file). File path:
  `Paths.get(PathManager.getLogPath(), "idea.log")`. Rotations: `idea.log.1`, `.2`.
  Stable platform API, callable from any thread.
- Log line regex (fixed IDEA log4j2 layout):
  `^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3}) \[\s*(\d+)\] +(TRACE|DEBUG|INFO|WARN|ERROR) - (\S+) - (.*)$`
- Everything else: `java.nio.file.Files` / `java.io.RandomAccessFile`.

## Threading & timeout

Pure file I/O. **No EDT, no `ReadAction`, no PSI.** MCP `suspend fun`s already
run on a background ktor coroutine; call blocking `RandomAccessFile.seek`/`read`
inline (optionally `withContext(Dispatchers.IO)` — not required). **No cache** —
log content is by definition fresh.

Hard 10 s cap:
- Tail buffer **1 MB** (`MAX_TAIL_BYTES = 1_048_576`) — <50 ms on sane disk.
  If file > 1 MB, `truncated=true`.
- `log.errors_since` rotation walk: **5 rotations max, 8 MB cumulative**.
- Wrap body in `withTimeoutOrNull(10_000) { … } ?: <empty + truncated=true>`.
- **Per-line regex** inside `withTimeoutOrNull(50)`; timeout ⇒ non-match.

## Edge cases

1. **Log file missing** — fresh start / headless fixture. Return empty result
   with `logPath` populated so the caller knows where it would be.
2. **Permission denied** — shared install where `idea.log` is `0640` for
   another uid. Catch `AccessDeniedException`, emit one synthetic
   `LogLine(raw="<permission denied: …>", parsed=false)`. Don't throw.
3. **Log just rotated; current has 2 lines** — `log.tail` returns those 2; we do
   NOT chase `idea.log.1` to fill `lines=N` (predictability > completeness).
   `log.errors_since` DOES walk rotations because the question spans time.
4. **Multi-line stacktraces** — `log.tail` emits each as `LogLine(parsed=false)`.
   `log.errors_since` with `groupByThrowable=true` collapses into the preceding
   ERROR's `stacktrace`. Continuation detection: line starts with `\t`, or
   `Caused by:`, or `\t... `.
5. **UTF-8 partial sequence at tail-buffer boundary** — reading the last 1 MB
   at an arbitrary byte offset may land mid-codepoint. Skip forward to the first
   `\n` before parsing — boundary clean. Apply `Utf8Truncation.truncateUtf8` to
   the final response for `maxBytes` cap.
6. **Regex catastrophic backtracking** — per-line `withTimeoutOrNull(50)`.
   `PatternSyntaxException` at compile ⇒ ignore filter, emit synthetic warning.
7. **Non-standard log lines** — third-party loggers / raw `println`. Emit as
   `LogLine(parsed=false, raw=…, …=null)`; severity/category filters skip them,
   regex still applies to `raw`.
8. **Malformed `sinceIsoTimestamp`** — fall back to `lastMinutes` (or 30 default),
   don't throw.
9. **`sinceIsoTimestamp` in the future** — return empty errors list, echo `since`.
10. **DST / timezone** — IDEA writes no zone; compare in JVM default via
    `LocalDateTime`. Document caveat for cross-zone debugging.
11. **Secret leakage** — `idea.log` can leak Bearer tokens, auth headers, OAuth
    URLs. Apply fixed regex set (`Bearer [A-Za-z0-9._\-]+`,
    `(?i)authorization:\s*\S+`, `(?i)token=([A-Za-z0-9._\-]{16,})`,
    `(?i)jba_auth=\S+`) to every `raw`/`message`/`stacktrace`; replace matched
    groups with `***REDACTED***`; set `redacted=true`. See open Q below.
12. **`limit` exceeded** — truncate, set `truncated=true`, set `total` to
    pre-truncation count so caller sees real cardinality.

## Files to create/modify

| Path | Op | What |
|------|----|------|
| `tools/LogToolset.kt` | Create | New `McpToolset` with `log_tail` + `log_errors_since` (thin wrappers around `LogReader`) |
| `core/LogReader.kt` | Create | Efficient backward-seek tail, parser, filters, stacktrace grouping, redaction |
| `model/LogInfo.kt` | Create | `LogLine`, `ErrorEntry`, two response types |
| `model/args/LogArgs.kt` | Create | `LogTailArgs`, `LogErrorsSinceArgs` |
| `META-INF/mcp-integration.xml` | Edit | Register `<mcpServer.mcpToolset implementation="…LogToolset"/>` |
| `src/test/kotlin/.../core/LogReaderTest.kt` | Create | Parser/filter/grouping/redaction unit tests on fixture strings — no IntelliJ runtime |
| `src/test/kotlin/.../core/LogReaderTailTest.kt` | Create | Backward-seek tail tests against `@TempDir` files |

`log.*` is always-available — register in main `mcp-integration.xml` next to
`ArchitectureToolset`. No new optional dependency.

## Test plan

**`LogReaderTest.kt`** — pure JVM, multi-line string fixtures:

- parses a vanilla IDEA log line into `LogLine(parsed=true, …)` with all fields.
- treats `\tat com.foo.Bar.baz(Bar.kt:42)` as `parsed=false`.
- `groupByThrowable=true` collapses ERROR + 5 `\tat` + `Caused by:` + 3 more
  `\tat` into one `ErrorEntry` with full `stacktrace`.
- `groupByThrowable=false` yields one entry per ERROR, drops continuations.
- severity "WARN" excludes INFO/DEBUG, includes WARN/ERROR.
- `categoryContains` is case-insensitive.
- regex `(a+)+b` against all-a's line times out and is treated as non-match.
- invalid regex `[unclosed` is silently ignored.
- redaction masks `Bearer abc123def`, sets `redacted=true`.
- redaction skips short non-secret `token=abc` (length floor).
- `sinceIsoTimestamp` cutoff prunes earlier lines.
- malformed `sinceIsoTimestamp` falls back to `lastMinutes`.

**`LogReaderTailTest.kt`** — temp-file backed:

- 3 MB fixture, `tail(lines=100)` returns exactly the last 100.
- 10-line file, `tail(lines=1000)` returns 10, `truncated=false`.
- 0-byte file ⇒ empty, `logPath` populated.
- no file ⇒ empty, expected `logPath`.
- emoji straddling the tail-buffer boundary — no `MalformedInputException`,
  no garbled chars in `raw`.
- `idea.log` + `idea.log.1`, `errors_since` cutoff predating rotation —
  both walked, ordering oldest-first.
- 4 × 3 MB rotation files — only first ~8 MB scanned, `truncated=true`.

Toolset wrappers are thin enough not to need a separate test class. One `runIde`
manual smoke (trigger an exception, call `log_errors_since`) closes the loop.

## Estimated effort

| Step | Hours |
|------|-------|
| `LogInfo` + `LogArgs` models | 0.5 |
| `LogReader.kt` — backward-seek tail + parser | 2.5 |
| Stacktrace grouping + secret redaction | 1.5 |
| `LogToolset.kt` + two `@McpDescription`s | 1 |
| `mcp-integration.xml` wiring | 0.1 |
| Unit tests (parser + tail-from-temp-file) | 2 |
| Doc-gen verification + manual `runIde` smoke | 0.5 |
| **Total** | **~1 day** |

## Open questions / risks

1. **JSON-shaped log lines** — some loggers emit structured JSON instead of the
   standard layout. **Skip for v1** — too niche; `raw` still surfaces them.
2. **Follow rotations for `log.tail`?** — would make `lines=N` "complete" across
   a fresh rotation but doubles I/O for the 99 % of calls that don't need it.
   **NO for v1**, optional v2 via `followRotations: Boolean = false`.
   `log.errors_since` walks rotations (cap 5) because the question spans time.
3. **Secret redaction** — recommend **on by default** with fixed regex set
   (Bearer / Authorization / token= / jba_auth=). Token-leak cost ≫ occasional
   false-positive mask. Surface `redacted=true` for audit. Configurability
   (extra patterns, opt-out) is v2 work via `IntrospectorSettings`.
4. **Unify both tools with a `since` arg on `log.tail`?** — Rejected. Different
   defaults (N-bounded vs time-bounded), shapes (lines vs grouped errors),
   and I/O profiles. Two focused tools beat one swiss-army knife.
5. **Live-tail / follow mode** — out of scope. MCP tools are request-response.
6. **`PathManager.getLogPath()` in unit tests** — returns a mock path with no
   IDE. `LogReader` takes the file path as a constructor arg so tests inject a
   `@TempDir` file; only `LogToolset` wrapper calls `PathManager`.

## References

- Existing code:
  - `tools/ArchitectureToolset.kt` — class shape for the new `LogToolset`.
  - `exec/AuditLogger.kt` — what `log.tail` will most often surface
    (`ide-introspector-audit` category).
  - `util/Utf8Truncation.kt` — `maxBytes` cap + UTF-8-safe tail-buffer trimming.
- IntelliJ source:
  - `PathManager`: https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/openapi/application/PathManager.java
  - Layout: `bin/log.xml` in any IDE install — confirms the
    `yyyy-MM-dd HH:mm:ss,SSS [thread] LEVEL - category - msg` format.
- JetBrains MCP equivalent: **none**. Closest is
  `execute_action_by_id("ShowLog")` which opens the file in Finder — useless
  for an agent.
