package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ComponentRegistry
import com.github.xepozz.ide.introspector.core.UiActionInvoker
import com.github.xepozz.ide.introspector.exec.UiActionSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Platform-level tests for [UiActionInvoker]. These tests build a real synthetic action,
 * register it with [ActionManager], then ask [UiActionInvoker] to invoke it against a
 * Swing component registered with [ComponentRegistry].
 *
 * The IDE's confirmation dialog logic lives in `UiActionConfirmationManager` and is NOT
 * exercised here — the test code calls [UiActionInvoker.invoke] directly, which is the
 * post-confirmation entry point. `setUp` still sets `enabled = true` so any code path
 * that consults the setting (audit log, etc.) sees the production-ready state.
 *
 * `tearDown` restores the settings defaults so the next test in the file (or a
 * subsequent test class) doesn't see opt-in residue.
 *
 * Headless caveat: `Component.isShowing` returns false in BasePlatformTestCase even after
 * we attach the component to a frame, because the frame never gets painted. The invoker
 * checks `isShowing` defensively — to make the test reachable, we wrap the component in
 * a [TestPanel] subclass that reports `true` for `isShowing()`. See [showingPanel].
 */
class UiActionInvokerPlatformTest : BasePlatformTestCase() {

    /** Tracks ids we register so tearDown can clean up. */
    private val registeredActionIds = mutableListOf<String>()

    /**
     * Tracks settings values prior to setUp so tearDown can restore them — failing to do
     * this would leave `enabled=true` in the shared application service and pollute every
     * subsequent test (or other plugin user) for the lifetime of the test JVM.
     */
    private var prevEnabled = false
    private var prevConfirm = true
    private var prevAudit = true

    override fun setUp() {
        super.setUp()
        val s = UiActionSettings.getInstance()
        prevEnabled = s.enabled
        prevConfirm = s.requireConfirmation
        prevAudit = s.auditEnabled
        // Tests run headless under CI — no human to click "Allow". Disable enabled so
        // any code path that consults requireConfirmation skips the dialog; tests that
        // need the in-toolset entry point set requireConfirmation=false separately.
        s.enabled = false
        s.requireConfirmation = false
        s.auditEnabled = false
    }

    override fun tearDown() {
        try {
            val am = ActionManager.getInstance()
            registeredActionIds.forEach { id ->
                try { am.unregisterAction(id) } catch (_: Throwable) { /* best effort */ }
            }
            registeredActionIds.clear()
            val s = UiActionSettings.getInstance()
            s.enabled = prevEnabled
            s.requireConfirmation = prevConfirm
            s.auditEnabled = prevAudit
        } finally {
            super.tearDown()
        }
    }

    // ============================================================================
    // Section 1: action resolution
    // ============================================================================

    /**
     * `findAction("DoesNotExist_<ts>")` must return null. Verifies the helper used by the
     * toolset to bail out early before invoking confirmation / EDT bouncing.
     */
    fun testFindActionReturnsNullForUnknownId() {
        val unknown = "ide.introspector.test.NotARealAction_${System.nanoTime()}"
        assertNull("Unknown action id must resolve to null", UiActionInvoker.findAction(unknown))
    }

    /**
     * Round-trip: register an action, [UiActionInvoker.findAction] must return that
     * exact instance (or at least an instance equal by reference / class).
     */
    fun testFindActionReturnsRegisteredAction() {
        val id = registerCountingAction("ide.introspector.test.Found_${System.nanoTime()}")
        val resolved = UiActionInvoker.findAction(id)
        assertNotNull("Registered action must be resolvable by id", resolved)
        assertTrue(resolved is RecordingAction)
    }

    // ============================================================================
    // Section 2: invoke() on the EDT against a showing component
    // ============================================================================

    /**
     * Happy path: invoke a recorded action against a showing component. Expect
     * `ok=true, executed=true` and the action's actionPerformed callback to have run.
     */
    fun testInvokeOnShowingComponentRunsActionPerformed() {
        val action = RecordingAction()
        val id = registerAction("ide.introspector.test.Happy_${System.nanoTime()}", action)
        val panel = showingPanel(JLabel("payload"))

        val result = onEdt { UiActionInvoker.invoke(action, panel) }

        assertTrue("expected ok=true, got $result", result.ok)
        assertTrue("expected executed=true, got $result", result.executed)
        assertEquals(
            "expected actionPerformed to run exactly once",
            1, action.performedCount,
        )
        assertNull("error must be null on the happy path", result.error)
    }

    /**
     * If the component reports `isShowing=false`, the invoker MUST short-circuit with
     * `component-not-showing` and not call `actionPerformed`.
     */
    fun testInvokeOnHiddenComponentReturnsComponentNotShowing() {
        val action = RecordingAction()
        registerAction("ide.introspector.test.Hidden_${System.nanoTime()}", action)
        val hidden = JPanel() // default Swing panel has isShowing=false in headless

        val result = onEdt { UiActionInvoker.invoke(action, hidden) }

        assertFalse("expected ok=false", result.ok)
        assertFalse("expected executed=false", result.executed)
        assertEquals(0, action.performedCount)
        assertEquals("component-not-showing", result.error)
    }

    /**
     * An action whose `update()` flips enabled=false must be reported as
     * `ok=true, executed=false` with the resolved presentation text — and
     * actionPerformed must NOT run.
     */
    fun testInvokeWithDisabledActionDoesNotInvokeActionPerformed() {
        val action = DisabledAction()
        registerAction("ide.introspector.test.Disabled_${System.nanoTime()}", action)
        val panel = showingPanel(JLabel("payload"))

        val result = onEdt { UiActionInvoker.invoke(action, panel) }

        assertTrue("expected ok=true even when disabled", result.ok)
        assertFalse("expected executed=false when update() disabled", result.executed)
        assertEquals("Disabled action must not perform", 0, action.performedCount)
    }

    /**
     * `presentationText` override is applied AFTER `update()` runs. We give the action a
     * predictable update() that sets text to "from-update", then pass an override and
     * confirm the override wins.
     */
    fun testPresentationTextOverrideIsAppliedAfterUpdate() {
        val action = TextSettingAction("from-update")
        registerAction("ide.introspector.test.TextOverride_${System.nanoTime()}", action)
        val panel = showingPanel(JLabel("payload"))

        val result = onEdt { UiActionInvoker.invoke(action, panel, presentationTextOverride = "from-override") }

        assertTrue("expected ok=true", result.ok)
        assertEquals("Override must replace update()'s text",
            "from-override", result.presentationText)
    }

    /**
     * A `presentationText` longer than [UiActionInvoker.PRESENTATION_TEXT_MAX] must be
     * truncated by the invoker. Defensive — the toolset trusts the agent's input only
     * inside the truncation helper.
     */
    fun testPresentationTextOverrideIsTruncated() {
        val action = TextSettingAction("ignored")
        registerAction("ide.introspector.test.TextTrunc_${System.nanoTime()}", action)
        val panel = showingPanel(JLabel("payload"))
        val tooLong = "x".repeat(UiActionInvoker.PRESENTATION_TEXT_MAX + 50)

        val result = onEdt { UiActionInvoker.invoke(action, panel, presentationTextOverride = tooLong) }

        assertNotNull(result.presentationText)
        assertEquals(UiActionInvoker.PRESENTATION_TEXT_MAX, result.presentationText!!.length)
    }

    /**
     * An action that throws inside `actionPerformed()` must be surfaced as
     * `ok=false, executed=true, error startsWith "perform-threw"`. The IDE itself must
     * not crash (the test JVM survival is the implicit assertion).
     */
    fun testInvokeWhenActionThrowsReturnsPerformThrewError() {
        val action = ThrowingAction()
        registerAction("ide.introspector.test.Throws_${System.nanoTime()}", action)
        val panel = showingPanel(JLabel("payload"))

        val result = onEdt { UiActionInvoker.invoke(action, panel) }

        assertFalse("expected ok=false", result.ok)
        assertTrue("expected executed=true (perform attempted)", result.executed)
        assertNotNull(result.error)
        assertTrue("expected perform-threw prefix, got ${result.error}",
            result.error!!.startsWith("perform-threw"))
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = runCatching(block)
        }
        return result!!.getOrThrow()
    }

    private fun registerAction(id: String, action: AnAction): String {
        val am = ActionManager.getInstance()
        // Some ids may collide between test runs in the same JVM — best-effort unregister first.
        try { am.unregisterAction(id) } catch (_: Throwable) { /* ignore */ }
        am.registerAction(id, action)
        registeredActionIds.add(id)
        return id
    }

    private fun registerCountingAction(id: String): String = registerAction(id, RecordingAction())

    /**
     * Headless test workaround: BasePlatformTestCase never realises a frame, so naturally
     * every Swing component reports `isShowing=false`. We override `isShowing` to true so
     * the invoker's defensive guard doesn't short-circuit every test.
     */
    private fun showingPanel(child: java.awt.Component): JPanel = TestPanel().also {
        it.add(child)
        it.size = java.awt.Dimension(120, 60)
    }

    /**
     * JPanel whose `isShowing()` is hardcoded to `true`. We do NOT touch the AWT realisation
     * machinery — just enough to let [UiActionInvoker.invoke] proceed past the `isShowing`
     * guard inside a headless test.
     */
    private class TestPanel : JPanel() {
        override fun isShowing(): Boolean = true
    }

    /** Records the count of `actionPerformed` calls. */
    private class RecordingAction : AnAction("Recording", "Test recording action", null) {
        var performedCount = 0
        override fun actionPerformed(e: AnActionEvent) { performedCount++ }
    }

    /** Always-disabled action — `update()` flips enabled=false. */
    private class DisabledAction : AnAction("Disabled", "Test disabled action", null) {
        var performedCount = 0
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
        override fun actionPerformed(e: AnActionEvent) { performedCount++ }
    }

    /** Sets a known text in `update()` so we can verify override-after-update ordering. */
    private class TextSettingAction(private val text: String) : AnAction("Text", "Test text setter", null) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = true
            e.presentation.text = text
        }
        override fun actionPerformed(e: AnActionEvent) { /* no-op */ }
    }

    /** Throws inside `actionPerformed`. */
    private class ThrowingAction : AnAction("Throws", "Test throwing action", null) {
        override fun actionPerformed(e: AnActionEvent) { throw IllegalStateException("simulated") }
    }
}
