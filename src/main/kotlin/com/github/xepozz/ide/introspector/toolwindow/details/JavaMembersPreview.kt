package com.github.xepozz.ide.introspector.toolwindow.details

import com.github.xepozz.ide.introspector.util.readActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java-PSI-backed implementation of the members section. ONLY referenced from inside
 * [MembersSection] guarded by a `Class.forName("com.intellij.psi.JavaPsiFacade")` check, so the
 * JVM only verifies this class when the Java module is loaded.
 */
internal object JavaMembersPreview {

    private const val METHODS_PREVIEW_LIMIT = 12

    fun build(project: Project, fqn: String): JComponent {
        val cls = readActionBlocking {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } ?: return notFound(fqn)

        val (methods, constructors) = cls.methods.partition { !it.isConstructor }
        val interfaces = cls.interfaces.mapNotNull { it.qualifiedName }
        val superFqn = cls.superClass?.qualifiedName?.takeIf { it != "java.lang.Object" }

        val form = DetailForm().apply {
            section("Members")
            superFqn?.let { row("Extends", FqnLink.render(project, it)) }
            if (interfaces.isNotEmpty()) row("Implements", stackedLinks(project, interfaces))
            row("Methods", JBLabel(methods.size.toString()))
            row("Constructors", JBLabel(constructors.size.toString()))
            row("Fields", JBLabel(cls.fields.size.toString()))
            if (cls.innerClasses.isNotEmpty()) row("Inner classes", JBLabel(cls.innerClasses.size.toString()))
            if (methods.isNotEmpty()) {
                val shown = minOf(methods.size, METHODS_PREVIEW_LIMIT)
                section("Declared methods (showing $shown of ${methods.size})")
                methods.take(METHODS_PREVIEW_LIMIT).forEach { row(it.name, signatureLabel(it)) }
            }
        }
        return form.build()
    }

    /** Vertical strip of FqnLinks for multi-valued rows like "Implements". */
    private fun stackedLinks(project: Project, fqns: List<String>): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        for (fqn in fqns) {
            val link = FqnLink.render(project, fqn)
            link.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(link)
        }
        return panel
    }

    private fun signatureLabel(m: PsiMethod): JComponent {
        val params = m.parameterList.parameters.joinToString(", ") { it.type.presentableText }
        val ret = m.returnType?.presentableText ?: ""
        val text = "(${params})${if (ret.isNotEmpty()) ": $ret" else ""}"
        return JBLabel(text).apply {
            foreground = UIUtil.getLabelInfoForeground()
            font = JBFont.small()
        }
    }

    private fun notFound(fqn: String): JComponent = JBLabel(
        "Class $fqn not resolvable in this project's scope."
    ).apply {
        foreground = UIUtil.getLabelInfoForeground()
        border = JBUI.Borders.empty(4, 0)
    }
}
