package com.github.xepozz.ide.introspector.exec

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Two-stage per-call confirmation dialog for `ui.invoke_action_on`.
 *
 *  Stage 1 — normal prompt with an in-memory "don't ask again for this session" opt-out.
 *  Stage 2 — fires ONLY when the actionId hits [UiActionBlocklist]. ALWAYS shown
 *            regardless of session bypass / `requireConfirmation=false`. No opt-out
 *            available; the user must approve every single blocklisted invocation.
 *
 * Session bypass state is SEPARATE from [ConfirmationManager.sessionBypass] — exec and
 * action consent live in different threat models, and approving "run Kotlin without
 * confirmation" should not silently approve "invoke any non-blocklisted action without
 * confirmation".
 */
object UiActionConfirmationManager {

    @Volatile private var sessionBypass: Boolean = false

    /**
     * Returns true iff the user approves the call (both stages, when stage 2 is required).
     *
     *  @param project           Owning project for dialog parenting.
     *  @param actionId          The id being invoked.
     *  @param actionText        Resolved `Presentation.text` (may be null when action absent).
     *  @param pluginOwner       Plugin id that owns the action, for trust attribution.
     *  @param component         Target Swing component — described in the dialog body.
     *  @param componentId       Registry id of the target — shown for traceability.
     *  @param requireConfirmation
     *                           Per-call override. When `false`, stage 1 is skipped unless
     *                           the actionId is on the blocklist (stage 2 still fires).
     *  @param isBlocklisted     Whether the actionId hits [UiActionBlocklist] (precomputed
     *                           by the caller — keeps blocklist resolution in one place).
     */
    fun confirm(
        project: Project?,
        actionId: String,
        actionText: String?,
        pluginOwner: String?,
        component: Component?,
        componentId: String,
        requireConfirmation: Boolean,
        isBlocklisted: Boolean,
    ): Decision {
        val settings = UiActionSettings.getInstance()

        // ----- Stage 1 -----
        val stage1Needed = requireConfirmation
            && settings.requireConfirmation
            && !sessionBypass
        if (stage1Needed) {
            val stage1Result = showDialog(
                project = project,
                title = "Invoke IDE action",
                stage = 1,
                actionId = actionId,
                actionText = actionText,
                pluginOwner = pluginOwner,
                component = component,
                componentId = componentId,
                allowSessionBypass = true,
            )
            if (!stage1Result.approved) return Decision.Rejected
            if (stage1Result.bypassForSession) sessionBypass = true
        }

        // ----- Stage 2 (blocklist forced) -----
        if (isBlocklisted) {
            val stage2Result = showDialog(
                project = project,
                title = "Confirm potentially destructive action",
                stage = 2,
                actionId = actionId,
                actionText = actionText,
                pluginOwner = pluginOwner,
                component = component,
                componentId = componentId,
                allowSessionBypass = false,
            )
            if (!stage2Result.approved) return Decision.RejectedBlocklist
        }
        return Decision.Approved
    }

    enum class Decision { Approved, Rejected, RejectedBlocklist }

    private data class DialogResult(val approved: Boolean, val bypassForSession: Boolean)

    private fun showDialog(
        project: Project?,
        title: String,
        stage: Int,
        actionId: String,
        actionText: String?,
        pluginOwner: String?,
        component: Component?,
        componentId: String,
        allowSessionBypass: Boolean,
    ): DialogResult {
        var approved = false
        var bypass = false
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = Dialog(
                project = project,
                title = title,
                stage = stage,
                actionId = actionId,
                actionText = actionText,
                pluginOwner = pluginOwner,
                component = component,
                componentId = componentId,
                allowSessionBypass = allowSessionBypass,
            )
            dialog.show()
            approved = dialog.exitCode != DialogWrapper.CANCEL_EXIT_CODE
            bypass = dialog.bypassForSession
        }
        return DialogResult(approved, bypass)
    }

    private class Dialog(
        project: Project?,
        title: String,
        private val stage: Int,
        private val actionId: String,
        private val actionText: String?,
        private val pluginOwner: String?,
        private val component: Component?,
        private val componentId: String,
        private val allowSessionBypass: Boolean,
    ) : DialogWrapper(project, true) {

        var bypassForSession = false

        init {
            this.title = title
            setOKButtonText(if (stage == 2) "Invoke anyway" else "Invoke")
            setCancelButtonText("Reject")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(8, 8))
            panel.preferredSize = Dimension(560, 280)
            val header = if (stage == 2) {
                "This action id matches the destructive-action blocklist. " +
                    "Approve only if you really want it to run."
            } else {
                "An MCP client wants to invoke this IntelliJ action against a specific component:"
            }
            panel.add(JBLabel(header), BorderLayout.NORTH)

            val grid = JPanel(GridLayout(0, 2, 6, 4))
            grid.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            grid.add(JBLabel("Action id:")); grid.add(JBLabel(actionId))
            grid.add(JBLabel("Action text:")); grid.add(JBLabel(actionText ?: "(unresolved)"))
            grid.add(JBLabel("Owning plugin:")); grid.add(JBLabel(pluginOwner ?: "(unknown)"))
            grid.add(JBLabel("Target component:")); grid.add(JBLabel(describeComponent(component)))
            grid.add(JBLabel("Component id:")); grid.add(JBLabel(componentId))
            panel.add(grid, BorderLayout.CENTER)

            if (allowSessionBypass) {
                val bypass = JCheckBox("Invoke and don't ask again for this session")
                bypass.addActionListener { bypassForSession = bypass.isSelected }
                panel.add(bypass, BorderLayout.SOUTH)
            }
            return panel
        }

        private fun describeComponent(c: Component?): String {
            if (c == null) return "(detached)"
            val b = c.bounds
            return "${c.javaClass.simpleName} ${b.width}x${b.height}@${b.x},${b.y}"
        }
    }
}
