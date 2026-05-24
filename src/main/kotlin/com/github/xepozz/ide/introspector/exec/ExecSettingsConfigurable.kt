package com.github.xepozz.ide.introspector.exec

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class ExecSettingsConfigurable : Configurable {

    // ---- Tier 2: Kotlin runtime execution ----
    private var enabledBox: JBCheckBox? = null
    private var confirmBox: JBCheckBox? = null
    private var auditBox: JBCheckBox? = null
    private var defaultTimeoutField: JBTextField? = null
    private var maxTimeoutField: JBTextField? = null

    // ---- Tier 2: UI action invocation ----
    private var uiActionEnabledBox: JBCheckBox? = null
    private var uiActionConfirmBox: JBCheckBox? = null
    private var uiActionAuditBox: JBCheckBox? = null

    override fun getDisplayName(): String = "IDE Introspector"

    override fun createComponent(): JComponent {
        val s = ExecSettings.getInstance()
        val enabled = JBCheckBox("Allow Kotlin code execution via exec.execute_kotlin_in_ide", s.enabled)
        val confirm = JBCheckBox("Show confirmation dialog before each execution", s.requireConfirmation)
        val audit = JBCheckBox("Write each execution to the IDE log (audit)", s.auditEnabled)
        val defaultTimeout = JBTextField(s.defaultTimeoutMs.toString())
        val maxTimeout = JBTextField(s.maxTimeoutMs.toString())

        enabledBox = enabled
        confirmBox = confirm
        auditBox = audit
        defaultTimeoutField = defaultTimeout
        maxTimeoutField = maxTimeout

        // Second labelled section: UI action invocation. SAME security category as Tier 2
        // exec — both opt-in, both per-call confirmation, both audited.
        val u = UiActionSettings.getInstance()
        val uiEnabled = JBCheckBox("Allow UI action invocation via ui.invoke_action_on", u.enabled)
        val uiConfirm = JBCheckBox("Show confirmation dialog before each action", u.requireConfirmation)
        val uiAudit = JBCheckBox("Write each action invocation to the IDE log (audit)", u.auditEnabled)
        uiActionEnabledBox = uiEnabled
        uiActionConfirmBox = uiConfirm
        uiActionAuditBox = uiAudit

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Tier 2: Runtime Kotlin execution"))
            .addComponent(enabled)
            .addComponent(confirm)
            .addComponent(audit)
            .addLabeledComponent("Default timeout (ms)", defaultTimeout)
            .addLabeledComponent("Maximum timeout (ms)", maxTimeout)
            .addSeparator(8)
            .addComponent(JBLabel("Tier 2: UI action invocation"))
            .addComponent(uiEnabled)
            .addComponent(uiConfirm)
            .addComponent(uiAudit)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = ExecSettings.getInstance()
        val u = UiActionSettings.getInstance()
        return s.enabled != enabledBox?.isSelected ||
            s.requireConfirmation != confirmBox?.isSelected ||
            s.auditEnabled != auditBox?.isSelected ||
            s.defaultTimeoutMs.toString() != defaultTimeoutField?.text?.trim() ||
            s.maxTimeoutMs.toString() != maxTimeoutField?.text?.trim() ||
            u.enabled != uiActionEnabledBox?.isSelected ||
            u.requireConfirmation != uiActionConfirmBox?.isSelected ||
            u.auditEnabled != uiActionAuditBox?.isSelected
    }

    override fun apply() {
        val s = ExecSettings.getInstance()
        s.enabled = enabledBox?.isSelected ?: false
        s.requireConfirmation = confirmBox?.isSelected ?: true
        s.auditEnabled = auditBox?.isSelected ?: true
        // Hard cap: see CLAUDE.md — no timeout above 10 s. The coerce here is the user-facing
        // guard; ExecSettings.maxTimeoutMs in code already defaults to MAX_TIMEOUT_MS.
        s.defaultTimeoutMs = readTimeoutMs(defaultTimeoutField?.text)
        s.maxTimeoutMs = readTimeoutMs(maxTimeoutField?.text)

        val u = UiActionSettings.getInstance()
        u.enabled = uiActionEnabledBox?.isSelected ?: false
        u.requireConfirmation = uiActionConfirmBox?.isSelected ?: true
        u.auditEnabled = uiActionAuditBox?.isSelected ?: true
    }

    private fun readTimeoutMs(input: String?): Long =
        (input?.trim()?.toLongOrNull() ?: MAX_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS)

    private companion object {
        /** Hard cap from CLAUDE.md — never raise above 10 000 ms. */
        const val MAX_TIMEOUT_MS = 10_000L
    }

    override fun reset() {
        val s = ExecSettings.getInstance()
        enabledBox?.isSelected = s.enabled
        confirmBox?.isSelected = s.requireConfirmation
        auditBox?.isSelected = s.auditEnabled
        defaultTimeoutField?.text = s.defaultTimeoutMs.toString()
        maxTimeoutField?.text = s.maxTimeoutMs.toString()

        val u = UiActionSettings.getInstance()
        uiActionEnabledBox?.isSelected = u.enabled
        uiActionConfirmBox?.isSelected = u.requireConfirmation
        uiActionAuditBox?.isSelected = u.auditEnabled
    }
}
