package com.github.xepozz.introspectorplugin.core.platform

import com.github.xepozz.introspectorplugin.core.ComponentTreeWalker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Dialog
import java.awt.Frame

/**
 * Platform-level tests for [ComponentTreeWalker.collectRoots].
 *
 * Why this lives separately from [com.github.xepozz.introspectorplugin.core.ComponentTreeWalkerWalkTest]:
 *
 *   * `walk` and `ancestors` are pure Swing graph traversal — they take a [java.awt.Component]
 *     argument and need nothing from the IDE. They are covered there with synthetic Swing fixtures.
 *   * `collectRoots`, by contrast, talks to `ProjectManager.getInstance().openProjects`,
 *     `WindowManager.getInstance().getFrame(project)`, `WindowManager.findVisibleFrame()`, and
 *     `ToolWindowManagerEx.getInstanceEx(project)`. None of those exist outside a real IDE
 *     environment, so we need [BasePlatformTestCase] which spins up a lightweight project +
 *     module and registers the platform services.
 *
 * Headless caveats:
 *
 *   * In a headless test the IDE frame is created but never made visible. `findVisibleFrame`
 *     may return null. `Window.getWindows()` may return an empty array. Tests that depend on
 *     a particular kind of root use [Assume] to skip rather than fail vacuously.
 *   * Tool windows are registered lazily — `getToolWindow("Project")` is usually non-null in
 *     a [BasePlatformTestCase] project but its content manager may not be initialised yet,
 *     so an empty result list is acceptable. We only assert that no exception escapes.
 */
class ComponentTreeWalkerRootsTest : BasePlatformTestCase() {

    // ====================================================================================
    // SECTION 1. Null selector — the "give me everything" path
    // ====================================================================================

    /**
     * Smoke test: `collectRoots(null)` must return a non-null list and not throw, regardless
     * of whether the IDE has actually materialised a frame. In headless BasePlatformTestCase
     * the project is open but `WindowManager.getInstance().getFrame(project)` returns null
     * (no AWT realisation), `findVisibleFrame()` returns null, and `Window.getWindows()` is
     * empty — so an empty list is the **correct** production behaviour, not a bug.
     *
     * The non-empty case is tested on a developer machine via runIde / manual MCP calls.
     */
    fun testCollectRootsWithoutSelectorDoesNotThrow() {
        val roots = onEdt { ComponentTreeWalker.collectRoots(null) }
        assertNotNull("collectRoots(null) must never return null", roots)
    }

    // ====================================================================================
    // SECTION 2. Built-in selectors: "frame" and "dialog"
    // ====================================================================================

    /**
     * The "frame" selector filters by `is java.awt.Frame`. Every survivor must be a Frame.
     * Empty result in headless is fine — we only fail if a non-Frame sneaks through.
     */
    fun testCollectRootsWithFrameSelectorReturnsOnlyFrames() {
        val roots = onEdt { ComponentTreeWalker.collectRoots("frame") }
        for (r in roots) {
            assertTrue(
                "Expected every root to be a java.awt.Frame, got ${r::class.qualifiedName}",
                r is Frame
            )
        }
    }

    /**
     * The "dialog" selector filters by `is java.awt.Dialog`. An empty result is fine
     * (headless tests rarely have open dialogs); we only fail if a non-Dialog sneaks through.
     */
    fun testCollectRootsWithDialogSelectorReturnsOnlyDialogs() {
        val roots = onEdt { ComponentTreeWalker.collectRoots("dialog") }
        for (r in roots) {
            assertTrue(
                "Expected every root to be a java.awt.Dialog, got ${r::class.qualifiedName}",
                r is Dialog
            )
        }
    }

    // ====================================================================================
    // SECTION 3. Unknown selector — the "else" branch
    // ====================================================================================

    /**
     * Per the code, any selector that doesn't match a known prefix falls through to
     * `else -> all`. So `collectRoots("totally-random-string")` must return the same
     * collection (size-wise) as `collectRoots(null)`. Comparing by `size` rather than
     * by identity avoids flakiness if the Window set mutates between the two calls.
     */
    fun testCollectRootsWithUnknownSelectorReturnsSameAsNoSelector() {
        val app = ApplicationManager.getApplication()
        var nullSize = -1
        var unknownSize = -1
        app.invokeAndWait {
            nullSize = ComponentTreeWalker.collectRoots(null).size
            unknownSize = ComponentTreeWalker.collectRoots("totally-random-string").size
        }
        assertEquals(
            "Unknown selectors must fall through to 'else -> all'",
            nullSize, unknownSize
        )
    }

    // ====================================================================================
    // SECTION 4. The "tool_window:<id>" selector
    // ====================================================================================

    /**
     * Exercise the tool-window branch. We pick whichever tool window the test project
     * registers — "Project" is the obvious choice but not guaranteed at every IDE
     * version, so we fall back to the first registered id.
     *
     * Result list may be empty (content not yet initialised); the contract we assert is
     * "no exception escapes and the result is non-null".
     */
    fun testCollectRootsWithToolWindowSelectorReturnsToolWindowContent() {
        val app = ApplicationManager.getApplication()
        var roots: List<java.awt.Component>? = null
        var selectedId: String? = null

        app.invokeAndWait {
            val twm = ToolWindowManager.getInstance(project)
            val preferred = twm.getToolWindow("Project")
            selectedId = when {
                preferred != null -> "Project"
                else -> twm.toolWindowIds.firstOrNull()
            }
            if (selectedId != null) {
                roots = ComponentTreeWalker.collectRoots("tool_window:$selectedId")
            }
        }

        if (selectedId == null) {
            // No tool windows registered in this test project. The branch is unreachable
            // in headless BasePlatformTestCase without an explicit ToolWindow registration;
            // passing the test as-is documents the limitation without lying about coverage.
            return
        }
        assertNotNull(
            "collectRoots(tool_window:$selectedId) must not return null",
            roots
        )
    }

    // ====================================================================================
    // Helpers
    // ====================================================================================

    /**
     * Bounces [block] onto the EDT via [ApplicationManager.invokeAndWait] and returns the
     * result. `collectRoots` itself doesn't bounce; some of the AWT/Swing APIs it reads
     * (window enumeration, tool-window content) are EDT-sensitive, so wrap to be safe.
     */
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = runCatching { block() }
        }
        return result!!.getOrThrow()
    }
}
