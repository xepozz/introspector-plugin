package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.HealthReporter
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level tests for [HealthReporter.indexingStatus].
 *
 * Exercises the reporter against the real `LightPlatformTestCase`-style fixture project,
 * which is in a stable, non-dumb state by the time `setUp` returns. We assert:
 *
 *   1. A fresh fixture project is reported as `dumbMode=false`, `isStartupComplete=true`,
 *      and shows up exactly once in `projectsIndexing`.
 *   2. The `projectHash` filter selects the fixture project by its `locationHash` and
 *      returns an empty per-project list for an unknown hash.
 *   3. The reflection-guarded `UnindexedFilesScannerExecutor` probe never throws on the
 *      test SDK — the worst it can do is return `false`.
 *
 * Memory snapshotting is covered by the pure-JVM `HealthReporterMemoryTest`; nothing
 * here repeats that.
 */
class HealthReporterIndexingPlatformTest : BasePlatformTestCase() {

    fun testFreshFixtureIsNotDumb() {
        val status = HealthReporter.indexingStatus()
        assertFalse(
            "Fresh BasePlatformTestCase project must not report dumb mode",
            status.dumbMode,
        )
        assertTrue(
            "isStartupComplete must be true for a fully-initialised fixture project",
            status.isStartupComplete,
        )
        assertTrue(
            "projectsIndexing must include at least the fixture project, got ${status.projectsIndexing}",
            status.projectsIndexing.isNotEmpty(),
        )
        // Sanity: the fixture project's locationHash should appear in the breakdown.
        val fixtureHash = project.locationHash
        assertTrue(
            "Fixture project hash $fixtureHash must appear in projectsIndexing breakdown ${status.projectsIndexing.map { it.projectHash }}",
            status.projectsIndexing.any { it.projectHash == fixtureHash },
        )
    }

    fun testProjectsIndexingPerProjectShape() {
        val status = HealthReporter.indexingStatus()
        for (entry in status.projectsIndexing) {
            assertFalse(
                "Per-project dumbModeActive must be false for fresh fixture, got entry=$entry",
                entry.dumbModeActive,
            )
            assertTrue(
                "projectName must be non-blank, got '${entry.projectName}'",
                entry.projectName.isNotBlank(),
            )
            assertTrue(
                "projectHash must be non-blank, got '${entry.projectHash}'",
                entry.projectHash.isNotBlank(),
            )
        }
    }

    fun testFilterByLocationHashMatchesFixture() {
        val fixtureHash = project.locationHash
        val filtered = HealthReporter.indexingStatus(projectHashFilter = fixtureHash)
        assertEquals(
            "Filtering by the fixture's locationHash should return exactly that project",
            1,
            filtered.projectsIndexing.count { it.projectHash == fixtureHash },
        )
    }

    fun testFilterByUnknownHashReturnsEmptyBreakdown() {
        val filtered = HealthReporter.indexingStatus(projectHashFilter = "nonsense-${System.nanoTime()}")
        assertTrue(
            "Unknown hash filter must produce an empty per-project list, got ${filtered.projectsIndexing}",
            filtered.projectsIndexing.isEmpty(),
        )
        // The top-level booleans still reflect global state — the spec explicitly calls this out.
        assertFalse(
            "Top-level dumbMode must still report global state (false for fresh fixture)",
            filtered.dumbMode,
        )
        assertTrue(
            "Top-level isStartupComplete must still report global state (true for fresh fixture)",
            filtered.isStartupComplete,
        )
    }

    fun testReflectionGuardDoesNotThrowOnTestSdk() {
        // Calling `indexingStatus` exercises the `UnindexedFilesScannerExecutor` reflection
        // probe. It must not throw — if it does, the reporter is broken on the test SDK.
        val status = HealthReporter.indexingStatus()
        assertNotNull("indexingStatus must always return a value, even when scanner probe fails", status)
        // scanningActive defaults to false when the scanner class is unavailable — assert the
        // reporter at least didn't crash by returning a sane shape.
        for (entry in status.projectsIndexing) {
            // No exception above is the real test; this assertion just keeps the loop honest.
            assertTrue(
                "scanningActive should be a Boolean (true OR false)",
                entry.scanningActive || !entry.scanningActive,
            )
        }
    }

    fun testEmptyProjectArraySimulatesHeadlessRun() {
        // Bypass the open-projects list to exercise the "no projects open" branch — what a
        // headless agent without a workspace would see.
        val status = HealthReporter.indexingStatus(
            projectHashFilter = null,
            projects = emptyArray(),
        )
        assertFalse("With no projects, dumbMode is false", status.dumbMode)
        assertTrue("With no projects, isStartupComplete is trivially true", status.isStartupComplete)
        assertTrue("With no projects, breakdown is empty", status.projectsIndexing.isEmpty())
    }

    fun testDumbModeFlipsReporterAndUnindexedScannerClassResolves() {
        // The `UnindexedFilesScannerExecutor` interface must be resolvable on the
        // test SDK at the canonical FQN — if not, `scanningActive` is dead.
        // (The probe itself must not throw; behaviour while *actually* scanning is hard
        // to deterministically trigger from a unit test, so we lock in the FQN here.)
        val scannerClass = runCatching {
            Class.forName(
                "com.intellij.openapi.project.UnindexedFilesScannerExecutor",
                false,
                HealthReporter::class.java.classLoader,
            )
        }.getOrNull()
        assertNotNull(
            "UnindexedFilesScannerExecutor must be resolvable at " +
                "com.intellij.openapi.project.UnindexedFilesScannerExecutor on the test SDK; " +
                "without it, ProjectIndexingState.scanningActive is permanently false.",
            scannerClass,
        )

        // Now flip dumb mode on the fixture and assert the reporter sees it. This guards
        // against future regressions where `dumbModeActive` silently goes dead.
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(
                "Sanity: DumbService.isDumb must be true inside runInDumbModeSynchronously",
                DumbService.getInstance(project).isDumb,
            )
            val statusInDumb = HealthReporter.indexingStatus()
            assertTrue(
                "Top-level dumbMode must be true while the fixture project is in dumb mode",
                statusInDumb.dumbMode,
            )
            val entry = statusInDumb.projectsIndexing.first { it.projectHash == project.locationHash }
            assertTrue(
                "Per-project dumbModeActive must be true for the dumb fixture, got $entry",
                entry.dumbModeActive,
            )
        }

        // After leaving dumb mode, the reporter must report quiescent state again.
        val statusAfter = HealthReporter.indexingStatus()
        assertFalse(
            "Top-level dumbMode must be false again once runInDumbModeSynchronously returns",
            statusAfter.dumbMode,
        )
    }

    fun testReporterIsConsistentAcrossCallsWithoutProjectMutation() {
        // Two back-to-back calls must produce equal results when nothing about the fixture
        // changed — guards against accidental statefulness leaking into the reporter.
        val first = HealthReporter.indexingStatus()
        val second = HealthReporter.indexingStatus()
        assertEquals(
            "Back-to-back snapshots must match when fixture state is stable",
            first.projectsIndexing.map { it.projectHash to it.dumbModeActive }.toSet(),
            second.projectsIndexing.map { it.projectHash to it.dumbModeActive }.toSet(),
        )
    }

    fun testProjectManagerOpenProjectsIncludesFixture() {
        // Sanity check that the fixture exposes itself via ProjectManager — if this fails
        // the per-project breakdown can't possibly be populated, so the failure here is a
        // friendlier signal than "tests pass but report nothing".
        val open = ProjectManager.getInstance().openProjects
        assertTrue(
            "Fixture project must be in ProjectManager.openProjects, got ${open.map { it.locationHash }}",
            open.any { it.locationHash == project.locationHash },
        )
    }
}
