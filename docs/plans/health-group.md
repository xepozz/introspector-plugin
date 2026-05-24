# `health.*` group — indexing & memory observability

## Purpose & motivation

A new `health.*` MCP tool group that gives agents a cheap, side-effect-free way
to ask **"is the IDE healthy enough to call X?"** before launching a long
sequence of PSI / index-dependent calls (`arch.*`, `psi.*`, `code.*`). Agents
have no way today to detect that the IDE is mid-indexing — they fire
`psi.find_usages`, hit `IndexNotReadyException` or a multi-minute stall, and
surface a confusing error. JetBrains' built-in MCP server exposes **neither**
indexing state nor JVM memory.

**Success criterion:** an agent runs `health.indexing_status` and skips/waits
before issuing index-bound PSI calls, and runs `health.memory` to detect a
near-OOM IDE before a heavy `screenshot.*` capture.

## Tool specifications

### `health.indexing_status`

**Signature:**
```kotlin
@McpTool(name = "health.indexing_status")
@McpDescription(""" |… see below … """)
suspend fun indexingStatus(
    @McpDescription("Optional project locationHash filter; omit to report all open projects.")
    projectHash: String? = null,
): IndexingStatus
```

**`@McpDescription` (verbatim, trim-margin):**

```
|Reports whether the IDE is currently indexing files or in "dumb mode", per open project.
|
|Use this when:
| - About to call any `psi.*`, `arch.*`, or `code.*` tool and you want to avoid
|   `IndexNotReadyException` / multi-minute stalls.
| - Debugging why a previous index-dependent call returned an empty / partial result.
| - Polling for "indexing finished" before kicking off a batch of PSI queries.
|
|Do NOT use this when:
| - You just want JVM memory — use `health.memory`.
| - You need per-file index state — this is project-level only.
| - You want to *trigger* indexing — this tool is read-only.
|
|Returns: {
|  dumbMode: Boolean,            // true if ANY open project is in dumb mode
|  isStartupComplete: Boolean,   // true once every open project finished post-startup
|  currentTask: String?,         // human-readable current dumb-mode task, if any
|  queuedTasks: Int,             // dumb-mode tasks queued behind the current one
|  projectsIndexing: [{ projectName, projectHash, dumbModeActive,
|                       indexingActive, scanningActive, currentTask? }]
|}
|
|Examples:
|  health.indexing_status                                # all open projects
|  health.indexing_status projectHash="a1b2c3"           # one project
```

**Args:** `projectHash: String? = null` — IntelliJ `Project.locationHash`; when
set, `projectsIndexing` is filtered to that single project (empty if no match).

### `health.memory`

**Signature:**
```kotlin
@McpTool(name = "health.memory")
@McpDescription(""" |… see below … """)
suspend fun memory(
    @McpDescription("If true, call System.gc() once before sampling. GC is a HINT — heap may not shrink. Default false.")
    gcBeforeRead: Boolean = false,
): MemorySnapshot
```

**`@McpDescription` (verbatim, trim-margin):**

```
|Reports JVM memory + a few IDE-specific counters from `java.lang.management` MXBeans.
|
|Use this when:
| - You suspect the IDE is near-OOM (slow response, freezes, GC thrashing).
| - You want a baseline before a heavy operation (`screenshot.capture`, large
|   `psi.get_tree`, `exec.execute_kotlin_in_ide`).
| - Diagnosing a memory-leak report from a user.
|
|Do NOT use this when:
| - You want per-object retention — use a real profiler (YourKit / JFR / VisualVM).
| - You need indexing state — use `health.indexing_status`.
| - `gcBeforeRead=true` looks like a fix — `System.gc()` is a HINT to the JVM
|   and may do nothing. Never rely on it to "free" memory.
|
|Returns: {
|  heap:      { used, max, committed, freeBytes },           // bytes
|  metaspace: { used, max },                                 // bytes; max may be -1 (unbounded)
|  nonHeap:   { used, max },                                 // bytes
|  threadCount: Int,
|  classCount:  Int,
|  gcs: [{ name, collectionCount, collectionTimeMs }],       // per-GC counters since JVM start
|  uptime:          Long,                                    // ms since JVM start
|  uptimeFormatted: String                                   // e.g. "1h 12m 03s"
|}
|
|Examples:
|  health.memory                            # cheap snapshot
|  health.memory gcBeforeRead=true          # rare; intrusive
```

**Args:** `gcBeforeRead: Boolean = false`. Default false because `System.gc()`
is a full stop-the-world hint and intrusive on a live IDE.

## Response models (`model/HealthInfo.kt`, all `@Serializable`)

```kotlin
data class IndexingStatus(val dumbMode: Boolean, val isStartupComplete: Boolean,
    val currentTask: String? = null, val queuedTasks: Int = 0,
    val projectsIndexing: List<ProjectIndexingState> = emptyList())
data class ProjectIndexingState(val projectName: String, val projectHash: String,
    val dumbModeActive: Boolean, val indexingActive: Boolean,
    val scanningActive: Boolean, val currentTask: String? = null)
data class MemorySnapshot(val heap: MemoryUsageBlock, val metaspace: MemoryUsageBlock,
    val nonHeap: MemoryUsageBlock, val threadCount: Int, val classCount: Int,
    val gcs: List<GcStat>, val uptime: Long, val uptimeFormatted: String)
data class MemoryUsageBlock(val used: Long, val max: Long, val committed: Long, val freeBytes: Long)
data class GcStat(val name: String, val collectionCount: Long, val collectionTimeMs: Long)
```

## IntelliJ / JDK APIs

Indexing — `com.intellij.openapi.project.DumbService#isDumb` (stable,
volatile-read), `DumbService#getCurrentTask()?.indicator?.text` for
`currentTask`, `DumbServiceImpl#queuedTasksCount` via reflection (internal;
fallback `0`), `com.intellij.util.indexing.UnindexedFilesScannerExecutor#isRunning`
(`@ApiStatus.Internal`; reflection-guarded, fallback `false`),
`com.intellij.openapi.startup.StartupManager#postStartupActivityPassed()`,
`ProjectManager#getOpenProjects()`, `Project#locationHash`.

Memory (JDK only, all thread-safe): `ManagementFactory.getMemoryMXBean()`
(heap + non-heap), `getMemoryPoolMXBeans()` (pool with name containing
`"Metaspace"`), `getGarbageCollectorMXBeans()`, `getThreadMXBean()`,
`getClassLoadingMXBean()`, `getRuntimeMXBean()`.

## Threading & timeouts

- **No EDT.** Both tools are safe from a background coroutine.
  `DumbService.isDumb` is documented as a volatile read; MXBeans are thread-safe
  per the JMX spec. No PSI / VFS / Swing access. No caching (defeats the "right
  now" semantics — agents poll `indexing_status` expecting it to change).
- **Timeout:** both calls return in <10 ms typical; `gcBeforeRead=true` may add
  a few hundred ms on a multi-GB heap. Well inside the 10 s cap (CLAUDE.md) —
  no `withTimeoutOrNull` wrapper needed.

## Edge cases

- **No project open** → `projectsIndexing: []`, `dumbMode: false`,
  `isStartupComplete: true`. Memory still reports normally.
- **Multiple projects indexing** → list every project; top-level `dumbMode` is
  OR-of-per-project; top-level `currentTask` is from the first dumb project in
  `openProjects` order (deterministic).
- **`projectHash` matches nothing** → top-level booleans still reflect global
  state, `projectsIndexing` is empty (called out in the description).
- **`UnindexedFilesScannerExecutor` / `DumbServiceImpl` internals** moved or
  renamed → reflection helper catches; missing boolean → `false`, counter → `0`,
  log once.
- **`System.gc()` no-op** under `-XX:+DisableExplicitGC` → documented.
- **Metaspace pool missing** (J9, Zing) → `MemoryUsageBlock(0, -1, 0, 0)`.
- **Negative `max`** is valid JMX ("unbounded") — pass through unchanged.
- **Headless / unit-test runtime** → `openProjects` empty; memory still useful.

## Files to create / modify

| Path | Op | What |
|------|----|------|
| `src/main/kotlin/.../tools/HealthToolset.kt` | Create | `McpToolset` with the two `@McpTool` methods; delegates to `HealthReporter`. |
| `src/main/kotlin/.../core/HealthReporter.kt` | Create | Pure logic. Memory path uses only `java.lang.management`; indexing path uses `DumbService` / `StartupManager` / `ProjectManager` + reflection guards for internals. |
| `src/main/kotlin/.../model/HealthInfo.kt` | Create | All `@Serializable` response types above. |
| `src/main/resources/META-INF/mcp-integration.xml` | Edit | Add `<mcpServer.mcpToolset implementation="…HealthToolset"/>`. No new `<depends>` line — both tools rely only on the core platform. |
| `src/test/kotlin/.../core/HealthReporterMemoryTest.kt` | Create | Unit. |
| `src/test/kotlin/.../core/platform/HealthReporterIndexingPlatformTest.kt` | Create | `BasePlatformTestCase`. |

## Test plan

Unit (`HealthReporterMemoryTest`, pure JVM):
1. `memory()` returns `heap.used > 0` and `heap.max > heap.used`.
2. `gcs` non-empty; every `name` non-blank.
3. `uptimeFormatted` matches `"Xh Ym Zs"` / `"Ym Zs"` / `"Zs"` patterns.
4. `gcBeforeRead=true` succeeds (no exception when GC is a no-op).
5. Metaspace fallback: with a synthetic empty pool list, returns `(0, -1, 0, 0)`.

Platform (`HealthReporterIndexingPlatformTest` extends `BasePlatformTestCase`):
1. Fresh fixture → `dumbMode=false`, `isStartupComplete=true`,
   `projectsIndexing.size == 1`.
2. `projectHash` filter: fixture's `locationHash` matches; `"nonsense"` returns empty.
3. Reflection guard: scanner check does not throw on the test SDK.

## Estimated effort

~0.5 day. Memory + model 1 h; indexing + reflection guards 1.5 h; toolset +
`mcp-integration.xml` 30 min; tests 1 h; `@McpDescription` polish + doc
regeneration 30 min.

## Open questions / risks

- **Index cache-dir size in `indexing_status`?** Could include
  `PathManager.getIndexRoot()` bytes. Rejected for v1: a `Files.walk` can take
  seconds and violates the "<10 ms" property. Revisit as a separate
  `health.disk_usage` tool if asked.
- **Delta tracking on `health.memory`?** Stash previous reading in a small
  `Service` and return `deltaSinceLastCall`. Rejected for v1 — agents can
  compute deltas across calls themselves; statefulness complicates an otherwise
  pure tool.
- **Combine into a single `health.status`?** Rejected. The two have different
  cadences (poll `indexing_status` until indexing finishes, then call `memory`
  once before a heavy op). Combining either over-fetches or needs a "section"
  arg that defeats the simplicity goal.
- **`UnindexedFilesScannerExecutor` / `DumbServiceImpl` internals** may move
  between IDE versions. Mitigation: reflection-guarded, graceful degradation.
  CI Plugin Verifier may warn — treat like the accepted warnings in CLAUDE.md.

## References

- Existing toolset pattern: `tools/ArchitectureToolset.kt` (same constructor-less
  `McpToolset` shape).
- Existing pure-logic reporter: `core/PluginInventory.kt` (used by both an
  `McpToolset` and the Platform Explorer tool window; `HealthReporter` follows
  the same shape).
- IntelliJ source: `platform/core-api/src/com/intellij/openapi/project/DumbService.kt`
  on `github.com/JetBrains/intellij-community`.
- JetBrains MCP equivalent: **none** — pure-additive group.
