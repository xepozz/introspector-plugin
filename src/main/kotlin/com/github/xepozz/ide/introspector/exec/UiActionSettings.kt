package com.github.xepozz.ide.introspector.exec

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Persistent settings for `ui.invoke_action_on`. Mirrors [ExecSettings] one-for-one:
 *
 *  - SHARED storage file `ide-introspector.xml`, distinct `@State.name` so the two
 *    settings classes don't trample each other.
 *  - Defaults are the SAFE choice: off, confirmation required, audit on. Flipping
 *    `enabled = true` is the user's explicit opt-in.
 *  - `blocklistedActionIds` is a USER-extendable list — the defaults in
 *    [UiActionBlocklist.DEFAULT_IDS] / [UiActionBlocklist.DEFAULT_PATTERNS] are NOT
 *    stored here. The list serialises out of the box; the v1 Configurable does not
 *    expose an editor for it, but power users can edit the XML on disk directly.
 */
@Service(Service.Level.APP)
@State(name = "IdeIntrospectorUiActionSettings", storages = [Storage("ide-introspector.xml")])
class UiActionSettings : PersistentStateComponent<UiActionSettings.State> {

    data class State(
        var enabled: Boolean = false,
        var requireConfirmation: Boolean = true,
        var auditEnabled: Boolean = true,
        var blocklistedActionIds: MutableList<String> = mutableListOf(),
    )

    private var state: State = State()

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var requireConfirmation: Boolean
        get() = state.requireConfirmation
        set(value) { state.requireConfirmation = value }

    var auditEnabled: Boolean
        get() = state.auditEnabled
        set(value) { state.auditEnabled = value }

    var blocklistedActionIds: MutableList<String>
        get() = state.blocklistedActionIds
        set(value) { state.blocklistedActionIds = value }

    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    companion object {
        fun getInstance(): UiActionSettings = service()
    }
}
