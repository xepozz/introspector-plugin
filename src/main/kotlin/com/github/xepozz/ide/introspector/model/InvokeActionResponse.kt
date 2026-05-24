package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Response shape for `ui.invoke_action_on`.
 *
 * Field semantics:
 *  - `ok` — `false` on user-rejected confirmation, blocklist rejection, unknown actionId,
 *    dead componentId, timeout, or thrown exception during `update()` / `actionPerformed()`.
 *  - `executed` — `true` ONLY if `actionPerformed()` was actually invoked. `false` when
 *    `update()` reported `enabled=false` (wrong context, dumb mode, no project) and we
 *    therefore did not fire the action.
 *  - `presentationText` — the action's `Presentation.text` after `update()` ran (or the
 *    optional override if provided). Useful for audit / debugging when `executed=false`.
 *  - `durationMs` — wall time spent in the single EDT round-trip (resolve + update + perform).
 *  - `error` — short machine-readable category when `ok=false`, e.g. `action-not-found:<id>`,
 *    `component-detached:<id>`, `component-not-showing`, `user-rejected`,
 *    `user-rejected-blocklist`, `edt-timeout`, or the exception message verbatim.
 */
@Serializable
data class InvokeActionResponse(
    val ok: Boolean,
    val actionId: String,
    val executed: Boolean,
    val presentationText: String? = null,
    val durationMs: Long,
    val error: String? = null,
)
