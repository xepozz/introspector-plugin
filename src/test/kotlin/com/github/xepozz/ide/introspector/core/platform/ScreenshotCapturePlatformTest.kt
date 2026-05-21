package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ScreenshotCapture
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Ignore
import java.awt.image.BufferedImage

/**
 * Platform-level tests for [ScreenshotCapture.captureActiveFrame].
 *
 * Why this lives separately from [com.github.xepozz.ide.introspector.core.ScreenshotCaptureSwingTest]:
 *
 *   * `captureComponent` works on any Swing component and is exercised with synthetic JButton
 *     fixtures there.
 *   * `captureActiveFrame` calls `WindowManager.getInstance().findVisibleFrame()`, which is
 *     IDE-platform-only — there is no way to set up a fake WindowManager from a plain JUnit test.
 *     So we use [BasePlatformTestCase], which registers the platform `WindowManager` and opens
 *     a test project so a frame can (in principle) exist.
 *
 * Headless caveats:
 *
 *   * Even with a project open, in a headless test the IDE frame is created but typically not
 *     made visible — `findVisibleFrame()` often returns `null`. In that case `captureActiveFrame`
 *     also returns null without throwing, and the tests that require an actual frame use
 *     [Assume] to skip rather than fail dishonestly.
 *
 * Out-of-scope here (covered elsewhere or impossible in this environment):
 *
 *   * `captureComponent` — covered by `ScreenshotCaptureSwingTest`.
 *   * `captureRect` — uses `java.awt.Robot`, which requires a display device. Marked `@Ignore`
 *     below so the limitation is visible in the test report.
 *   * `fitWithinBudget` / `encodedSize` — covered by `ImageBudgetTest`.
 */
class ScreenshotCapturePlatformTest : BasePlatformTestCase() {

    // ====================================================================================
    // SECTION 1. Smoke: never throw
    // ====================================================================================

    /**
     * The cheapest, most important contract: regardless of whether a visible frame exists,
     * `captureActiveFrame()` returns gracefully (either a [BufferedImage] or `null`) and never
     * propagates an exception. MCP clients call this through a `suspend` boundary where a
     * thrown exception becomes a failed tool call.
     */
    fun testCaptureActiveFrameDoesNotThrow() {
        onEdt {
            // Either outcome is acceptable. The static return type guarantees a BufferedImage?
            // — what we care about is that no exception escapes. If the call returns, we pass.
            val result: BufferedImage? = ScreenshotCapture.captureActiveFrame()
            // Touch the value so a dead-code optimiser doesn't elide the call.
            @Suppress("UNUSED_VARIABLE") val width = result?.width
        }
    }

    // ====================================================================================
    // SECTION 2. When a frame is visible
    // ====================================================================================

    /**
     * If [WindowManager.findVisibleFrame] returns a frame, [ScreenshotCapture.captureActiveFrame]
     * must paint it into a [BufferedImage]. Headless tests typically have no visible frame —
     * in that case this test passes by early-return (BasePlatformTestCase extends JUnit 3
     * TestCase which doesn't honour `org.junit.Assume`).
     */
    fun testCaptureActiveFrameReturnsBufferedImageWhenFrameVisible() {
        onEdt {
            val frame = WindowManager.getInstance().findVisibleFrame() ?: return@onEdt
            val image = ScreenshotCapture.captureActiveFrame()
            assertNotNull("captureActiveFrame should return a BufferedImage when a frame is visible", image)
            // Silence "unused" — touch the frame to make the dependency on it explicit.
            @Suppress("UNUSED_VARIABLE") val w = frame.width
        }
    }

    /**
     * If a frame exists, the captured image must have non-degenerate dimensions matching
     * the frame's bounds. `captureComponent` clamps width/height to `max(1, …)` so the
     * minimum is 1x1. Headless: early-return as a pass.
     */
    fun testCaptureActiveFrameImageHasFrameDimensions() {
        onEdt {
            val frame = WindowManager.getInstance().findVisibleFrame() ?: return@onEdt
            val image = ScreenshotCapture.captureActiveFrame()
            assertNotNull(image)
            val img = image!!
            assertTrue("image width must be >= 1, got ${img.width}", img.width >= 1)
            assertTrue("image height must be >= 1, got ${img.height}", img.height >= 1)
            if (frame.width > 0) assertEquals(frame.width, img.width)
            if (frame.height > 0) assertEquals(frame.height, img.height)
        }
    }

    // ====================================================================================
    // SECTION 3. captureRect — out of reach in headless
    // ====================================================================================

    /**
     * `captureRect` constructs a `java.awt.Robot`, which throws `AWTException` when no display
     * device is available. There is no portable way to exercise it from a headless CI run.
     *
     * This method is intentionally NOT named `testFoo` — `BasePlatformTestCase` extends JUnit 3
     * `TestCase`, which discovers tests purely by name and ignores `@Ignore`. Renaming this to
     * `ignored_*` keeps the limitation documented in source without producing a phantom failing
     * test. The `@Ignore` annotation is left as a discovery hint for IDE inspections.
     */
    @Ignore("java.awt.Robot requires a display device; not exercisable in headless CI")
    fun ignored_captureRectIsHeadlessUnfriendly() {
        // Intentionally empty — the KDoc above is the documentation.
    }

    // ====================================================================================
    // Helpers
    // ====================================================================================

    /**
     * Runs [block] on the EDT (via [ApplicationManager.invokeAndWait]) and rethrows any
     * exception on the calling thread so JUnit sees the real failure rather than a swallowed
     * EDT trace.
     */
    private fun onEdt(block: () -> Unit) {
        var thrown: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            }
        }
        thrown?.let { throw it }
    }
}
