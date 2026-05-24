package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.HealthReporter
import com.github.xepozz.ide.introspector.model.IndexingStatus
import com.github.xepozz.ide.introspector.model.MemorySnapshot
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool

/**
 * `health.*` — observability tools that let an agent ask "is the IDE healthy enough to
 * call X?" before launching a long sequence of PSI / index-dependent calls.
 *
 * Both tools are background-thread-safe (no EDT, no ReadAction). The heavy lifting lives
 * in [HealthReporter] so the logic is unit-testable without an IntelliJ runtime.
 *
 * See [docs/plans/health-group.md] for the underlying tool specification.
 */
class HealthToolset : McpToolset {

    @McpTool(name = "health.indexing_status")
    @McpDescription(
        """
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
        """
    )
    suspend fun health_indexing_status(
        @McpDescription("Optional project locationHash filter; omit to report all open projects.")
        projectHash: String? = null,
    ): IndexingStatus = HealthReporter.indexingStatus(projectHashFilter = projectHash)

    @McpTool(name = "health.memory")
    @McpDescription(
        """
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
        """
    )
    suspend fun health_memory(
        @McpDescription("If true, call System.gc() once before sampling. GC is a HINT — heap may not shrink. Default false.")
        gcBeforeRead: Boolean = false,
    ): MemorySnapshot = HealthReporter.memory(gcBeforeRead = gcBeforeRead)
}
