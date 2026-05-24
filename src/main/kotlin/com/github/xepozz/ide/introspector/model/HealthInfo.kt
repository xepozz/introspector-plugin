package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Response models for the `health.*` MCP tool group — observability into the live IDE's
 * indexing state and JVM memory usage. All types are pure data, no IntelliJ Platform deps,
 * so they can be unit-tested without spinning up a sandbox IDE.
 *
 * See [docs/plans/health-group.md] for the underlying tool specification.
 */

/** Aggregated indexing state across one or more open projects. */
@Serializable
data class IndexingStatus(
    /** True if ANY open project is in dumb mode (index unavailable). */
    val dumbMode: Boolean,
    /** True once every open project finished its post-startup activities. */
    val isStartupComplete: Boolean,
    /** Human-readable description of the current dumb-mode task, if any. */
    val currentTask: String? = null,
    /** Dumb-mode tasks queued behind the current one. May be 0 even while indexing. */
    val queuedTasks: Int = 0,
    /** Per-project breakdown — one entry per open project (empty if no projects open). */
    val projectsIndexing: List<ProjectIndexingState> = emptyList(),
)

/** Per-project indexing snapshot. */
@Serializable
data class ProjectIndexingState(
    val projectName: String,
    /** IntelliJ `Project.locationHash` — stable across the project's lifetime. */
    val projectHash: String,
    /** True if the project is currently in dumb mode (PSI / index access restricted). */
    val dumbModeActive: Boolean,
    /** True if a dumb-mode task is currently running (best-effort; may be false during scan). */
    val indexingActive: Boolean,
    /** True if `UnindexedFilesScannerExecutor` is scanning for files to index. */
    val scanningActive: Boolean,
    /** Current dumb-mode task description for THIS project, if any. */
    val currentTask: String? = null,
)

/** JVM memory + IDE-level JMX counters; cheap snapshot suitable for polling. */
@Serializable
data class MemorySnapshot(
    val heap: MemoryUsageBlock,
    /** Metaspace pool. `max` may be -1 ("unbounded"); pool may be missing on non-HotSpot JVMs. */
    val metaspace: MemoryUsageBlock,
    val nonHeap: MemoryUsageBlock,
    val threadCount: Int,
    val classCount: Int,
    val gcs: List<GcStat>,
    /** Milliseconds since JVM start. */
    val uptime: Long,
    /** [uptime] rendered as `"Xh Ym Zs"` / `"Ym Zs"` / `"Zs"`. */
    val uptimeFormatted: String,
)

/** One JMX memory pool snapshot. Bytes; `max == -1` means "unbounded" per JMX spec. */
@Serializable
data class MemoryUsageBlock(
    val used: Long,
    val max: Long,
    val committed: Long,
    /** `committed - used`, clamped at 0. Cheap convenience field for agents. */
    val freeBytes: Long,
)

/** One garbage-collector's cumulative counters since JVM start. */
@Serializable
data class GcStat(
    val name: String,
    val collectionCount: Long,
    val collectionTimeMs: Long,
)
