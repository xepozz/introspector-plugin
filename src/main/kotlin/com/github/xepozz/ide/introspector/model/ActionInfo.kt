package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Metadata for one registered [com.intellij.openapi.actionSystem.AnAction] entry —
 * the JSON shape returned by `arch.list_actions`.
 *
 * Field semantics:
 *  - [id] is the action id from `ActionManager` (e.g. `"EditorCopy"`); always non-null.
 *  - [text] / [description] come from the action's template presentation. Both are null
 *    when the action is a registry stub that has not been class-loaded yet (the action
 *    walker keeps the entry rather than throwing — stubs are real, just unresolved).
 *  - [providedByPluginId] / [providedByPluginName] are sourced from the reverse map built
 *    from every `PluginDescriptor` via `ActionManagerEx.getPluginActions(pluginId)`. Both
 *    are null for actions registered by the core platform with no discoverable owner
 *    (the project's models elsewhere use `null` rather than the string `"unknown"`).
 *  - [keyboardShortcuts] is the comma-joined human-readable form, e.g. `"Ctrl+C, Ctrl+Insert"`,
 *    or null when the keymap has no binding for that id.
 *  - [category] is reserved for the action-group ancestry path (e.g. `"Main Menu/Refactor"`).
 *    Computing it for every entry is too expensive at the cap, so we ship the field but
 *    populate it lazily later — current implementation leaves it null.
 *  - [iconPath] is the icon resource path from `templatePresentation.icon?.toString()`,
 *    or null when no icon is registered. Best-effort: some icons stringify to garbage.
 *  - [isInternal] mirrors `<action internal="true">`. The platform consumes that flag at
 *    parser time — actions marked `internal="true"` are NOT registered when the IDE is
 *    running without `-Didea.is.internal=true`, and when it IS running with internal mode
 *    on they're registered identically to regular actions with no observable flag on the
 *    [com.intellij.openapi.actionSystem.AnAction] instance. There is no public, supported
 *    way to recover that bit post-registration, so this field is **always false** today
 *    and the `includeInternal` arg on `arch.list_actions` is a no-op (kept for forward
 *    compatibility and cache-key stability). See [com.github.xepozz.ide.introspector.core.ActionInventory.resolveAction]
 *    kdoc for the long version.
 */
@Serializable
data class ActionInfo(
    val id: String,
    val text: String? = null,
    val description: String? = null,
    val providedByPluginId: String? = null,
    val providedByPluginName: String? = null,
    val keyboardShortcuts: String? = null,
    val category: String? = null,
    val iconPath: String? = null,
    val isInternal: Boolean = false,
)

/**
 * Response wrapper for `arch.list_actions`.
 *
 *  - [actions] — at most `limit` entries (clamped 1..2000).
 *  - [total] — the count BEFORE applying `limit`. When the walk was cut short by the
 *    10 s hard timeout we cannot know the would-be total — in that case it equals
 *    `actions.size` and [truncated] is `true`.
 *  - [truncated] — `true` if either the limit was hit OR the timeout fired mid-walk.
 */
@Serializable
data class ListActionsResponse(
    val actions: List<ActionInfo>,
    val total: Int,
    val truncated: Boolean,
)
