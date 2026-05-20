package com.github.xepozz.introspectorplugin.exec

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "IdeIntrospectMcpExecSettings", storages = [Storage("ide-introspect-mcp.xml")])
class ExecSettings : PersistentStateComponent<ExecSettings.State> {

    data class State(
        var enabled: Boolean = false,
        var requireConfirmation: Boolean = true,
        var defaultTimeoutMs: Long = 30_000,
        var maxTimeoutMs: Long = 5L * 60_000,
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
