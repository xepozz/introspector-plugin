package com.github.xepozz.ide.introspector.toolwindow.details

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Em-dash used to indicate "no value" in form rows. Centralised so a future refactor (i18n,
 *  unicode tweaks, replacement with an icon) only touches one place. */
internal const val DASH = "—"

/** Renders blank/null strings as [DASH] — used wherever we display a possibly-missing value. */
internal fun String?.orDash(): String = if (isNullOrBlank()) DASH else this

/** ActionLink with a no-padding border — used everywhere in the details panel so links sit
 *  flush with surrounding form cells. */
internal fun actionLink(text: String, onClick: () -> Unit): com.intellij.ui.components.ActionLink =
    com.intellij.ui.components.ActionLink(text) { _ -> onClick() }.apply {
        border = JBUI.Borders.empty()
    }

/**
 * Lightweight 2-column key-value form used in detail views. Built directly on GridBagLayout
 * (instead of FormBuilder) because we want value cells to be arbitrary [JComponent]s — Chips,
 * ActionLinks, ChipStrips — not just text. FormBuilder also defaults to vertical fill which we
 * don't want here.
 *
 * Usage:
 *   DetailForm()
 *     .row("Plugin id", JBLabel(p.id))
 *     .row("Class", FqnLink.render(project, fqn))
 *     .section("Members")
 *     .row("Methods", JBLabel("12"))
 *     .build()
 */
class DetailForm {
    private val rows = mutableListOf<Row>()

    private sealed class Row {
        data class KeyValue(val key: String, val value: JComponent) : Row()
        data class Section(val title: String) : Row()
        data class Separator(val unit: Unit = Unit) : Row()
        data class Custom(val component: JComponent) : Row()
    }

    fun row(key: String, value: JComponent): DetailForm = apply { rows.add(Row.KeyValue(key, value)) }
    fun row(key: String, value: String?): DetailForm = row(key, JBLabel(value.orDash()))
    fun section(title: String): DetailForm = apply { rows.add(Row.Section(title)) }
    fun separator(): DetailForm = apply { rows.add(Row.Separator()) }
    fun custom(component: JComponent): DetailForm = apply { rows.add(Row.Custom(component)) }

    fun build(): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            isOpaque = false
        }
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 0, 2, 8)
        }
        var y = 0
        for (row in rows) when (row) {
            is Row.Section -> {
                gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 2; gbc.weightx = 1.0
                gbc.insets = Insets(10, 0, 4, 0)
                val header = JBLabel(row.title).apply {
                    font = JBFont.label().asBold()
                    foreground = UIUtil.getLabelInfoForeground()
                    border = BorderFactory.createMatteBorder(
                        0, 0, 1, 0, UIUtil.getBoundsColor()
                    )
                }
                panel.add(header, gbc)
                gbc.insets = Insets(2, 0, 2, 8)
                y++
            }
            is Row.Separator -> {
                gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 2; gbc.weightx = 1.0
                panel.add(JPanel().apply {
                    isOpaque = false
                    border = BorderFactory.createMatteBorder(
                        0, 0, 1, 0, UIUtil.getBoundsColor()
                    )
                    preferredSize = java.awt.Dimension(0, 8)
                }, gbc)
                y++
            }
            is Row.KeyValue -> {
                gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 1; gbc.weightx = 0.0
                val keyLabel = JBLabel(row.key).apply {
                    foreground = UIUtil.getLabelInfoForeground()
                    horizontalAlignment = SwingConstants.LEFT
                    verticalAlignment = SwingConstants.TOP
                }
                panel.add(keyLabel, gbc)
                gbc.gridx = 1; gbc.weightx = 1.0
                row.value.alignmentX = Component.LEFT_ALIGNMENT
                panel.add(row.value, gbc)
                y++
            }
            is Row.Custom -> {
                gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 2; gbc.weightx = 1.0
                panel.add(row.component, gbc)
                y++
            }
        }
        // Push everything to the top.
        gbc.gridx = 0; gbc.gridy = y; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel().apply { isOpaque = false }, gbc)
        return panel
    }
}

/**
 * Page header — large title, optional subtitle, optional chip strip. Sits above the form rows.
 */
fun pageHeader(title: String, subtitle: String? = null, chips: ChipStrip? = null): JComponent {
    val panel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(12, 12, 4, 12)
        isOpaque = false
    }
    val center = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        val titleLabel = JBLabel(title).apply {
            font = JBFont.h3()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(titleLabel)
        if (!subtitle.isNullOrBlank()) {
            val sub = JBLabel(subtitle).apply {
                foreground = UIUtil.getLabelInfoForeground()
                font = JBFont.small()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(2)
            }
            add(sub)
        }
        if (chips != null) {
            chips.alignmentX = Component.LEFT_ALIGNMENT
            chips.border = JBUI.Borders.emptyTop(6)
            add(chips)
        }
    }
    panel.add(center, BorderLayout.CENTER)
    return panel
}
