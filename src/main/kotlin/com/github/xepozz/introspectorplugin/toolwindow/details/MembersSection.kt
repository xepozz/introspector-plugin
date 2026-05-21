package com.github.xepozz.introspectorplugin.toolwindow.details

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Resolves a class FQN against the project and renders a "Members" detail-form section showing
 * superclass, interfaces, and member counts. Java PSI access is mandatory; in IDEs without the
 * Java module (PyCharm CE, RubyMine, GoLand, …) the section is silently skipped.
 *
 * The Java-only work lives in [JavaMembersPreview] which is loaded lazily — this object has
 * no imports of Java-PSI classes, so it's safe to load even when those types are absent.
 */
object MembersSection {

    /**
     * Returns a body component for the members section, or null when:
     *   - the Java module is not loaded (non-Java IDE),
     *   - the FQN is blank,
     *   - PSI lookup failed.
     *
     * Caller is expected to add a `section("Members")` header before this component.
     */
    fun build(project: Project, fqn: String?): JComponent? {
        if (fqn.isNullOrBlank()) return null
        if (!javaModuleAvailable) return null
        return try {
            JavaMembersPreview.build(project, fqn)
        } catch (_: NoClassDefFoundError) {
            null
        } catch (_: Throwable) {
            // Index might be in dumb mode, or the class is broken — show a tiny notice instead
            // of breaking the whole detail panel.
            null
        }
    }

    /** Lazily computed — cached for the JVM lifetime. */
    private val javaModuleAvailable: Boolean by lazy {
        try {
            Class.forName("com.intellij.psi.JavaPsiFacade")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}

/**
 * Builds a fallback notice when the Java module is absent — separate so callers can decide
 * whether to render it (some screens may prefer to omit the section entirely).
 */
fun absentJavaModuleNotice(): JComponent = JBLabel(
    "Install the Java module to see class members."
).apply {
    foreground = UIUtil.getLabelInfoForeground()
    border = JBUI.Borders.empty(4, 0)
}
