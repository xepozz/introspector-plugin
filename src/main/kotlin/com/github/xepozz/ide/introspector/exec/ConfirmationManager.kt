package com.github.xepozz.ide.introspector.exec

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Per-call confirmation dialog with a "don't ask again for this session" opt-out.
 *
 * Session bypass is in-memory only — resets on IDE restart.
 */
object ConfirmationManager {

    @Volatile private var sessionBypass: Boolean = false

    fun confirm(project: Project, code: String): Boolean {
        if (sessionBypass) return true
        if (!ExecSettings.getInstance().requireConfirmation) return true

        var allowed = false
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = Dialog(project, code)
            dialog.show()
            allowed = dialog.exitCode != DialogWrapper.CANCEL_EXIT_CODE
            if (dialog.bypassForSession) sessionBypass = true
        }
        return allowed
    }

    private class Dialog(project: Project, private val code: String) : DialogWrapper(project, true) {
        var bypassForSession = false

        init {
            title = "Execute Kotlin in IDE"
            setOKButtonText("Execute")
            setCancelButtonText("Reject")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(8, 8))
            panel.preferredSize = Dimension(640, 360)
            panel.add(JBLabel("An MCP client wants to compile and run this Kotlin code inside this IDE:"), BorderLayout.NORTH)
            val area = JTextArea(code).apply {
                isEditable = false
                font = font.deriveFont(12f)
            }
            panel.add(JBScrollPane(area), BorderLayout.CENTER)
            val bypass = JCheckBox("Execute and don't ask again for this session")
            bypass.addActionListener { bypassForSession = bypass.isSelected }
            panel.add(bypass, BorderLayout.SOUTH)
            return panel
        }
    }
}
