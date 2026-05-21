package com.github.xepozz.ide.introspector.exec

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "IdeIntrospectorExecSettings", storages = [Storage("ide-introspector.xml")])
class ExecSettings : PersistentStateComponent<ExecSettings.State> {

    data class State(
        var enabled: Boolean = false,
        var requireConfirmation: Boolean = true,
        // Project policy: NEVER raise timeouts above 10 s — see CLAUDE.md.
        var defaultTimeoutMs: Long = 10_000,
        var maxTimeoutMs: Long = 10_000,
        var auditEnabled: Boolean = true,
    )

    private var state: State = State()

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var requireConfirmation: Boolean
        get() = state.requireConfirmation
        set(value) { state.requireConfirmation = value }

    var defaultTimeoutMs: Long
        get() = state.defaultTimeoutMs
        set(value) { state.defaultTimeoutMs = value }

    var maxTimeoutMs: Long
        get() = state.maxTimeoutMs
        set(value) { state.maxTimeoutMs = value }

    var auditEnabled: Boolean
        get() = state.auditEnabled
        set(value) { state.auditEnabled = value }

    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    companion object {
        fun getInstance(): ExecSettings = service()
    }
}
