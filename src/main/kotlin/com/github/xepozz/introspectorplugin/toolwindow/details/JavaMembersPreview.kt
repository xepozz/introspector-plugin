package com.github.xepozz.introspectorplugin.toolwindow.details

import com.github.xepozz.introspectorplugin.util.readActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java-PSI-backed implementation of the members section. ONLY referenced from inside
 * [MembersSection] guarded by a `Class.forName("com.intellij.psi.JavaPsiFacade")` check, so the
 * JVM only verifies this class when the Java module is loaded.
 */
internal object JavaMembersPreview {

    fun build(project: Project, fqn: String): JComponent? {
        val cls = readActionBlocking {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } ?: return notFound(fqn)

        val form = DetailForm()
        form.section("Members")

        // Type relationships first — single line each, both clickable so users can chase the
        // inheritance graph without leaving the panel.
        val superFqn = cls.superClass?.qualifiedName?.takeIf { it != "java.lang.Object" }
        if (superFqn != null) {
            form.row("Extends", FqnLink.render(project, superFqn))
        }
        val interfaces = cls.interfaces.mapNotNull { it.qualifiedName }
        if (interfaces.isNotEmpty()) {
            for ((i, iface) in interfaces.withIndex()) {
                form.row(if (i == 0) "Implements" else "", FqnLink.render(project, iface))
            }
        }

        val methods = cls.methods.filter { !it.isConstructor }
        val constructors = cls.methods.filter { it.isConstructor }
        val fields = cls.fields
        val inners = cls.innerClasses

        form.row("Methods", JBLabel(methods.size.toString()))
        form.row("Constructors", JBLabel(constructors.size.toString()))
        form.row("Fields", JBLabel(fields.size.toString()))
        if (inners.isNotEmpty()) form.row("Inner classes", JBLabel(inners.size.toString()))

        // Show up to 12 method signatures inline. Picks declared (not inherited) methods, which
        // is the most useful set for the typical "what does this extension implement?" question.
        if (methods.isNotEmpty()) {
            form.section("Declared methods (showing ${minOf(methods.size, 12)} of ${methods.size})")
            for (m in methods.take(12)) {
                form.row(m.name, signatureLabel(m))
            }
        }

        return form.build()
    }

    private fun signatureLabel(m: PsiMethod): JComponent {
        val params = m.parameterList.parameters.joinToString(", ") { it.type.presentableText }
        val ret = m.returnType?.presentableText ?: ""
        val text = "(${params})${if (ret.isNotEmpty()) ": $ret" else ""}"
        return JBLabel(text).apply {
            foreground = UIUtil.getLabelInfoForeground()
            font = font.deriveFont(font.size2D - 1f)
        }
    }

    private fun notFound(fqn: String): JComponent {
        val panel = JPanel().apply { isOpaque = false; border = JBUI.Borders.empty(4, 0) }
        panel.add(JBLabel("Class $fqn not resolvable in this project's scope.").apply {
            foreground = UIUtil.getLabelInfoForeground()
        })
        return panel
    }
}
