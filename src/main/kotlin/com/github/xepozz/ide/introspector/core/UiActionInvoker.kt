package com.github.xepozz.ide.introspector.core

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import java.awt.Component

/**
 * Headless logic backing `ui.invoke_action_on`. Resolves the action + component, builds a
 * synthetic [AnActionEvent], runs `update()` (respecting DumbAware), and fires
 * `actionPerformed()` via [ActionUtil.performActionDumbAwareWithCallbacks] so other plugins'
 * `AnActionListener` instances see it as a real user click — important for audit
 * consistency in 3rd-party plugins.
 *
 * All three sub-operations (DataContext, update, perform) MUST happen in one EDT trip — the
 * `DataContext` produced by [DataManager] is tied to the EDT pump that produced it. The
 * toolset wraps the whole `invoke` call in a single `onEdtBlocking { … }`.
 *
 * Returns a plain [Result] data class so the unit tests can construct expected values
 * without touching the `@Serializable` response shape directly.
 */
object UiActionInvoker {

    /** Maximum length of a `presentationText` override (audit-only). */
    const val PRESENTATION_TEXT_MAX = 200

    /** Suffix appended when [truncatePresentationText] truncates. */
    const val TRUNCATION_ELLIPSIS = "…"

    /**
     * Outcome from a single [invoke] call. The toolset maps this onto the public
     * `InvokeActionResponse` model — keeping the two types separate means the invoker
     * itself has no dependency on `@Serializable` / classloader-sensitive types.
     */
    data class Result(
        val ok: Boolean,
        val executed: Boolean,
        val presentationText: String? = null,
        val durationMs: Long,
        val error: String? = null,
    )

    /**
     * Must be called on the EDT — the caller wraps the entire `invoke` block in
     * `onEdtBlocking { … }`.
     *
     * @param action            The resolved [AnAction] to invoke.
     * @param component         The target component the synthetic [DataContext] is rooted at.
     * @param presentationTextOverride
     *                          Optional cosmetic override applied AFTER `update()`. Use only
     *                          for audit / dialog display; the action's own `update()` will
     *                          have populated the canonical text first.
     */
    fun invoke(
        action: AnAction,
        component: Component,
        presentationTextOverride: String? = null,
    ): Result {
        val startNs = System.nanoTime()
        // Defensive: the component could have detached between lookup and the EDT trip.
        if (!component.isShowing) {
            return Result(
                ok = false,
                executed = false,
                durationMs = elapsedMs(startNs),
                error = "component-not-showing",
            )
        }

        val dataContext = try {
            DataManager.getInstance().getDataContext(component)
        } catch (t: Throwable) {
            return Result(
                ok = false,
                executed = false,
                durationMs = elapsedMs(startNs),
                error = "data-context-failed:${t.javaClass.simpleName}",
            )
        }

        val event = try {
            AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dataContext)
        } catch (t: Throwable) {
            return Result(
                ok = false,
                executed = false,
                durationMs = elapsedMs(startNs),
                error = "event-creation-failed:${t.javaClass.simpleName}",
            )
        }

        // Drive `update()` explicitly and read the resulting presentation. `ActionUtil
        // .lastUpdateAndCheckDumb` is deprecated in 252 and its dumb-aware guard now skips
        // the update() call entirely for non-dumb-aware actions in DumbMode — meaning
        // `event.presentation.isEnabled` can stay stale (whatever the action's template
        // presentation initialised with) and we'd execute a disabled action.
        val enabled = try {
            action.update(event)
            event.presentation.isEnabled
        } catch (t: Throwable) {
            return Result(
                ok = false,
                executed = false,
                presentationText = event.presentation.text,
                durationMs = elapsedMs(startNs),
                error = "update-threw:${t.javaClass.simpleName}:${t.message}",
            )
        }

        // Apply the cosmetic override AFTER update() so we don't get overwritten.
        val overriddenText = truncatePresentationText(presentationTextOverride)
        if (overriddenText != null) {
            event.presentation.text = overriddenText
        }
        val resolvedText = event.presentation.text

        if (!enabled) {
            // Correct outcome — caller / agent sees why the action grayed out.
            return Result(
                ok = true,
                executed = false,
                presentationText = resolvedText,
                durationMs = elapsedMs(startNs),
            )
        }

        return try {
            ActionUtil.performActionDumbAwareWithCallbacks(action, event)
            Result(
                ok = true,
                executed = true,
                presentationText = resolvedText,
                durationMs = elapsedMs(startNs),
            )
        } catch (t: Throwable) {
            Result(
                ok = false,
                executed = true,
                presentationText = resolvedText,
                durationMs = elapsedMs(startNs),
                error = "perform-threw:${t.javaClass.simpleName}:${t.message}",
            )
        }
    }

    /**
     * Truncates [text] to at most [PRESENTATION_TEXT_MAX] characters (incl. the ellipsis).
     * Returns null when [text] is null. Used for both the dialog and the audit log so the
     * agent's `presentationText` cannot blow up either.
     */
    fun truncatePresentationText(text: String?): String? {
        if (text == null) return null
        if (text.length <= PRESENTATION_TEXT_MAX) return text
        return text.take(PRESENTATION_TEXT_MAX - TRUNCATION_ELLIPSIS.length) + TRUNCATION_ELLIPSIS
    }

    /**
     * Centralised error-message formatters. Tests assert against these strings, so
     * keep the format stable — `key:value` is the convention.
     */
    fun formatActionNotFound(actionId: String): String = "action-not-found:$actionId"

    fun formatComponentDetached(componentId: String): String = "component-detached:$componentId"

    /**
     * Convenience used by the toolset (and tests) so callers don't have to keep
     * [ActionManager] reflection inline.
     */
    fun findAction(actionId: String): AnAction? = try {
        ActionManager.getInstance().getAction(actionId)
    } catch (_: Throwable) {
        null
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
}
