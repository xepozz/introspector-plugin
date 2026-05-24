package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.GcStat
import com.github.xepozz.ide.introspector.model.IndexingStatus
import com.github.xepozz.ide.introspector.model.MemorySnapshot
import com.github.xepozz.ide.introspector.model.MemoryUsageBlock
import com.github.xepozz.ide.introspector.model.ProjectIndexingState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.StartupManager
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryUsage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-logic reporter for the `health.*` tool group.
 *
 * Both methods are designed to run from a background coroutine — they touch only:
 *   - `DumbService.isDumb` — racy by design. JetBrains kdoc says to wrap in a read-action
 *      for strict correctness; we deliberately don't because a health snapshot is
 *      intrinsically a point-in-time sample, and a read-action could deadlock with the
 *      write-action that *changes* dumb mode.
 *   - `StartupManager.postStartupActivityPassed()` (thread-safe boolean read).
 *   - `ProjectManager.getOpenProjects()` (snapshot copy; safe from any thread).
 *   - `java.lang.management.*` MXBeans (thread-safe per the JMX spec).
 *
 * `UnindexedFilesScannerExecutor.isRunning` (a `StateFlow<Boolean>`) is reached
 * reflectively because the class is `@ApiStatus.Internal`. If the API moves or renames
 * in a future IDE build, [loadClass] catches and we fall back to `false` plus a
 * one-time `LOG.warn`.
 *
 * No caching: agents poll `indexing_status` waiting for indexing to finish, so a stale
 * cached answer would defeat the entire purpose. Both calls return in <10 ms typical.
 */
object HealthReporter {

    private val LOG = logger<HealthReporter>()

    /** One-shot latch so we log a missing `UnindexedFilesScannerExecutor` once, not on every poll. */
    private val scannerClassMissingLogged = AtomicBoolean(false)

    // ====================================================================================
    // Indexing
    // ====================================================================================

    /**
     * Aggregates indexing/dumb-mode state across [projects] (default: every open project).
     *
     * If [projectHashFilter] is non-null, the per-project breakdown is filtered to projects
     * whose [Project.getLocationHash] matches; top-level booleans still reflect the global
     * across-all-projects state so an agent can tell "the IDE is busy but not on _my_ project".
     */
    fun indexingStatus(
        projectHashFilter: String? = null,
        projects: Array<Project> = ProjectManager.getInstance().openProjects,
    ): IndexingStatus {
        // Top-level booleans always reflect global state — compute from the unfiltered list.
        val anyDumb = projects.any { DumbService.getInstance(it).isDumb }
        // `Iterable.all` on empty trivially returns true → headless / no-project case is correct.
        val allStartupComplete = projects.all { startupComplete(it) }

        val filteredProjects = if (projectHashFilter == null) {
            projects.toList()
        } else {
            projects.filter { it.locationHash == projectHashFilter }
        }
        val perProject = filteredProjects.map { snapshotProject(it) }

        return IndexingStatus(
            dumbMode = anyDumb,
            isStartupComplete = allStartupComplete,
            projectsIndexing = perProject,
        )
    }

    private fun snapshotProject(project: Project): ProjectIndexingState {
        val dumb = DumbService.getInstance(project).isDumb
        val scanning = scannerRunning(project)
        return ProjectIndexingState(
            projectName = project.name,
            projectHash = project.locationHash,
            dumbModeActive = dumb,
            scanningActive = scanning,
        )
    }

    /** True if the project has finished post-startup activities. */
    private fun startupComplete(project: Project): Boolean {
        return runCatching { StartupManager.getInstance(project).postStartupActivityPassed() }
            .getOrElse { true }
    }

    /**
     * Reflective fetch of `UnindexedFilesScannerExecutor.getInstance(project).isRunning`.
     * The class is `@ApiStatus.Internal` and the impl has moved between IDE versions, so
     * we load by FQN under a try-catch. The canonical interface (verified on JetBrains
     * intellij-community master) lives at `com.intellij.openapi.project`; older builds
     * exposed it under `com.intellij.util.indexing.*` as a class. Fall back to `false`.
     */
    private fun scannerRunning(project: Project): Boolean {
        val klass = loadClass(
            "com.intellij.openapi.project.UnindexedFilesScannerExecutor",
            "com.intellij.util.indexing.UnindexedFilesScannerExecutor",
            "com.intellij.util.indexing.dependencies.UnindexedFilesScannerExecutor",
            "com.intellij.util.indexing.diagnostic.dependencies.UnindexedFilesScannerExecutor",
        ) ?: run {
            if (scannerClassMissingLogged.compareAndSet(false, true)) {
                LOG.warn(
                    "UnindexedFilesScannerExecutor not found at any known FQN; " +
                        "ProjectIndexingState.scanningActive will always be false on this IDE."
                )
            }
            return false
        }
        return runCatching {
            val getInstance = klass.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 1 }
                ?: return false
            val executor = getInstance.invoke(null, project) ?: return false
            // `isRunning` may be exposed as a property (`getIsRunning` / `isRunning`) or a method.
            val isRunningMethod = executor.javaClass.methods.firstOrNull {
                (it.name == "isRunning" || it.name == "getIsRunning") && it.parameterCount == 0
            } ?: return false
            val value = isRunningMethod.invoke(executor)
            when (value) {
                is Boolean -> value
                else -> {
                    // Modern API returns a StateFlow<Boolean>. Try `.value`.
                    runCatching {
                        val getValue = value?.javaClass?.methods?.firstOrNull {
                            it.name == "getValue" && it.parameterCount == 0
                        }
                        (getValue?.invoke(value) as? Boolean) == true
                    }.getOrDefault(false)
                }
            }
        }.getOrElse {
            LOG.debug("UnindexedFilesScannerExecutor#isRunning reflection failed", it)
            false
        }
    }

    private fun loadClass(vararg names: String): Class<*>? {
        val cl = HealthReporter::class.java.classLoader
        for (n in names) {
            try {
                return Class.forName(n, /* initialize = */ false, cl)
            } catch (_: Throwable) {
                // Try next.
            }
        }
        return null
    }

    // ====================================================================================
    // Memory
    // ====================================================================================

    /**
     * Cheap JVM memory snapshot. [gcBeforeRead] is a *hint* — `System.gc()` may do nothing
     * under `-XX:+DisableExplicitGC`. Default false because forcing a full GC stalls the
     * IDE EDT-side scrubbers.
     *
     * [memoryMXBean] / [poolMXBeans] are injectable so the metaspace-pool-fallback can be
     * tested without spinning up a sandbox JVM.
     */
    fun memory(
        gcBeforeRead: Boolean = false,
        memoryMXBean: MemoryMXBean = ManagementFactory.getMemoryMXBean(),
        poolMXBeans: List<MemoryPoolMXBean> = ManagementFactory.getMemoryPoolMXBeans(),
    ): MemorySnapshot {
        if (gcBeforeRead) {
            // Hint only — the JVM may ignore it.
            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
        }

        val heap = memoryMXBean.heapMemoryUsage.toBlock()
        val nonHeap = memoryMXBean.nonHeapMemoryUsage.toBlock()
        val metaspace = poolMXBeans
            .firstOrNull { it.name.contains("Metaspace", ignoreCase = true) }
            ?.usage
            ?.toBlock()
            ?: MemoryUsageBlock(used = 0L, max = -1L, committed = 0L, freeBytes = 0L)

        val gcs = ManagementFactory.getGarbageCollectorMXBeans().map { gc ->
            GcStat(
                name = gc.name ?: "unknown",
                collectionCount = gc.collectionCount.coerceAtLeast(0L),
                collectionTimeMs = gc.collectionTime.coerceAtLeast(0L),
            )
        }
        val threadCount = ManagementFactory.getThreadMXBean().threadCount
        val classCount = ManagementFactory.getClassLoadingMXBean().loadedClassCount
        val uptime = ManagementFactory.getRuntimeMXBean().uptime.coerceAtLeast(0L)

        return MemorySnapshot(
            heap = heap,
            metaspace = metaspace,
            nonHeap = nonHeap,
            threadCount = threadCount,
            classCount = classCount,
            gcs = gcs,
            uptime = uptime,
            uptimeFormatted = formatUptime(uptime),
        )
    }

    /**
     * Renders milliseconds as one of `"Zs"`, `"Ym Zs"`, `"Xh Ym Zs"`, or
     * `"Xd Yh Zm Ws"` (the day form is for sanity on never-restarted IDEs).
     * Single-digit fields are zero-padded *within* a multi-field rendering so the
     * agent gets a stable column width to grep.
     */
    fun formatUptime(uptimeMs: Long): String {
        val clamped = uptimeMs.coerceAtLeast(0L)
        val totalSec = clamped / 1_000L
        val days = totalSec / 86_400L
        val hours = (totalSec / 3_600L) % 24L
        val minutes = (totalSec / 60L) % 60L
        val seconds = totalSec % 60L

        return when {
            days > 0 -> "${days}d %02dh %02dm %02ds".format(hours, minutes, seconds)
            hours > 0 -> "${hours}h %02dm %02ds".format(minutes, seconds)
            minutes > 0 -> "${minutes}m %02ds".format(seconds)
            else -> "${seconds}s"
        }
    }

    private fun MemoryUsage.toBlock(): MemoryUsageBlock {
        val u = used.coerceAtLeast(0L)
        val c = committed.coerceAtLeast(0L)
        // JMX returns -1 for "unbounded" max; pass through unchanged.
        val m = max
        val free = (c - u).coerceAtLeast(0L)
        return MemoryUsageBlock(used = u, max = m, committed = c, freeBytes = free)
    }
}
