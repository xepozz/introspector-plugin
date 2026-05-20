package com.github.xepozz.introspectorplugin.exec

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class ExecSettingsConfigurable : Configurable {

    private var enabledBox: JBCheckBox? = null
    private var confirmBox: JBCheckBox? = null
    private var auditBox: JBCheckBox? = null
    private var defaultTimeoutField: JBTextField? = null
    private var maxTimeoutField: JBTextField? = null

    override fun getDisplayName(): String = "IDE Introspect MCP"

    override fun createComponent(): JComponent {
        val s = ExecSettings.getInstance()
        enabledBox = JBCheckBox("Allow Kotlin code execution via exec.execute_kotlin_in_ide", s.enabled)
        confirmBox = JBCheckBox("Show confirmation dialog before each execution", s.requireConfirmation)
        auditBox = JBCheckBox("Write each execution to the IDE log (audit)", s.auditEnabled)
        defaultTimeoutField = JBTextField(s.defaultTimeoutMs.toString())
        maxTimeoutField = JBTextField(s.maxTimeoutMs.toString())

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Tier 2: Runtime Kotlin execution"))
            .addComponent(enabledBox!!)
            .addComponent(confirmBox!!)
            .addComponent(auditBox!!)
            .addLabeledComponent("Default timeout (ms)", defaultTimeoutField!!)
            .addLabeledComponent("Maximum timeout (ms)", maxTimeoutField!!)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = ExecSettings.getInstance()
        return s.enabled != enabledBox?.isSelected ||
            s.requireConfirmation != confirmBox?.isSelected ||
            s.auditEnabled != auditBox?.isSelected ||
            s.defaultTimeoutMs.toString() != defaultTimeoutField?.text?.trim() ||
            s.maxTimeoutMs.toString() != maxTimeoutField?.text?.trim()
    }

    override fun apply() {
        val s = ExecSettings.getInstance()
        s.enabled = enabledBox?.isSelected ?: false
        s.requireConfirmation = confirmBox?.isSelected ?: true
        s.auditEnabled = auditBox?.isSelected ?: true
        s.defaultTimeoutMs = defaultTimeoutField?.text?.trim()?.toLongOrNull() ?: 30_000L
        s.maxTimeoutMs = maxTimeoutField?.text?.trim()?.toLongOrNull() ?: 300_000L
    }

    override fun reset() {
        val s = ExecSettings.getInstance()
        enabledBox?.isSelected = s.enabled
        confirmBox?.isSelected = s.requireConfirmation
        auditBox?.isSelected = s.auditEnabled
        defaultTimeoutField?.text = s.defaultTimeoutMs.toString()
        maxTimeoutField?.text = s.maxTimeoutMs.toString()
    }
}
