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

/**
 * Pure-logic reporter for the `health.*` tool group.
 *
 * Both methods are designed to run from a background coroutine — they touch only:
 *   - `DumbService.isDumb` (documented volatile-read; thread-safe).
 *   - `StartupManager.postStartupActivityPassed()` (thread-safe boolean read).
 *   - `ProjectManager.getOpenProjects()` (snapshot copy; safe from any thread).
 *   - `java.lang.management.*` MXBeans (thread-safe per the JMX spec).
 *
 * A few interesting bits of state (`DumbServiceImpl.queuedTasksCount`,
 * `UnindexedFilesScannerExecutor.isRunning`) are only reachable via internal API. Those
 * reads are wrapped in [reflectField] / [reflectBoolean] — if the API moves or renames in
 * a future IDE build, the reflection helper catches and we fall back to a conservative
 * default (`0` / `false`) and log the failure once.
 *
 * No caching: agents poll `indexing_status` waiting for indexing to finish, so a stale
 * cached answer would defeat the entire purpose. Both calls return in <10 ms typical.
 */
object HealthReporter {

    private val LOG = logger<HealthReporter>()

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
        val perProject = projects.map { snapshotProject(it) }
        val anyDumb = perProject.any { it.dumbModeActive }
        val allStartupComplete = perProject.all { startupComplete(projectByHash(projects, it.projectHash)) }
            // Empty project list ⇒ trivially "startup complete".
            .let { if (perProject.isEmpty()) true else it }
        val firstDumb = perProject.firstOrNull { it.dumbModeActive }
        val currentTask = firstDumb?.currentTask
        val queuedTasks = firstDumb?.let { queuedTasksFor(projectByHash(projects, it.projectHash)) } ?: 0

        val filtered = if (projectHashFilter == null) {
            perProject
        } else {
            perProject.filter { it.projectHash == projectHashFilter }
        }
        return IndexingStatus(
            dumbMode = anyDumb,
            isStartupComplete = allStartupComplete,
            currentTask = currentTask,
            queuedTasks = queuedTasks,
            projectsIndexing = filtered,
        )
    }

    private fun projectByHash(projects: Array<Project>, hash: String): Project? =
        projects.firstOrNull { it.locationHash == hash }

    private fun snapshotProject(project: Project): ProjectIndexingState {
        val dumb = DumbService.getInstance(project).isDumb
        val currentTask = currentTaskText(project)
        val scanning = scannerRunning(project)
        return ProjectIndexingState(
            projectName = project.name,
            projectHash = project.locationHash,
            dumbModeActive = dumb,
            // Best-effort: a dumb-mode task that exposes a text indicator counts as "indexing".
            // If we're dumb but the current task text is null, scanning is often the cause.
            indexingActive = dumb && currentTask != null,
            scanningActive = scanning,
            currentTask = currentTask,
        )
    }

    /** True if the project has finished post-startup activities. */
    private fun startupComplete(project: Project?): Boolean {
        if (project == null) return true
        return runCatching { StartupManager.getInstance(project).postStartupActivityPassed() }
            .getOrElse { true }
    }

    /**
     * Best-effort read of `DumbService.getCurrentTask()?.indicator?.text`.
     * The `getCurrentTask` method is `@ApiStatus.Internal` on `DumbServiceImpl` and not
     * present on the public interface, so we go through reflection.
     */
    private fun currentTaskText(project: Project): String? {
        val service = DumbService.getInstance(project)
        val task = runCatching {
            val method = service.javaClass.methods.firstOrNull { it.name == "getCurrentTask" && it.parameterCount == 0 }
                ?: return@runCatching null
            method.invoke(service)
        }.getOrElse {
            LOG.debug("DumbService#getCurrentTask reflection failed", it)
            null
        } ?: return null
        // `task.indicator?.text` — ProgressIndicator getText().
        val indicator = runCatching {
            val m = task.javaClass.methods.firstOrNull { it.name == "getIndicator" && it.parameterCount == 0 }
            m?.invoke(task)
        }.getOrNull() ?: return null
        val text = runCatching {
            val m = indicator.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
            m?.invoke(indicator) as? String
        }.getOrNull()
        return text?.takeIf { it.isNotBlank() }
    }

    /** Reads `DumbServiceImpl.queuedTasksCount`, defaulting to 0 if reflection fails. */
    private fun queuedTasksFor(project: Project?): Int {
        if (project == null) return 0
        val service = DumbService.getInstance(project)
        return runCatching {
            // Try a getter first (Kotlin-style property), then a raw field.
            service.javaClass.methods
                .firstOrNull { it.name == "getQueuedTasksCount" && it.parameterCount == 0 }
                ?.invoke(service)
                ?.let { (it as? Number)?.toInt() }
                ?: readIntField(service, "queuedTasksCount")
                ?: 0
        }.getOrElse {
            LOG.debug("DumbServiceImpl#queuedTasksCount reflection failed", it)
            0
        }
    }

    /**
     * Reflective fetch of `UnindexedFilesScannerExecutor.getInstance(project).isRunning`.
     * The class is `@ApiStatus.Internal` and has moved package between IDE versions, so we
     * load it by FQN under a try-catch and fall back to `false`.
     */
    private fun scannerRunning(project: Project): Boolean {
        val klass = loadClass(
            "com.intellij.util.indexing.UnindexedFilesScannerExecutor",
            "com.intellij.util.indexing.dependencies.UnindexedFilesScannerExecutor",
            "com.intellij.util.indexing.diagnostic.dependencies.UnindexedFilesScannerExecutor",
        ) ?: return false
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
                    // Some builds return a StateFlow / KMutableProperty. Try `.value`.
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
        for (n in names) {
            try {
                return Class.forName(n)
            } catch (_: Throwable) {
                // Try next.
            }
        }
        return null
    }

    private fun readIntField(target: Any, fieldName: String): Int? {
        return runCatching {
            val f = target.javaClass.getDeclaredField(fieldName)
            f.isAccessible = true
            (f.get(target) as? Number)?.toInt()
        }.getOrNull()
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
