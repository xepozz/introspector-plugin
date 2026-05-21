package com.github.xepozz.ide.introspector.toolwindow.details

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent

/**
 * Renders a fully-qualified class name as a clickable link. Clicking attempts to navigate to
 * the source file via two strategies, in order:
 *
 *   1. JavaPsiFacade.findClass — exact, but requires the com.intellij.modules.java module.
 *      Resolved by reflection so this view stays loadable in non-Java IDEs (PyCharm CE etc.).
 *   2. FilenameIndex by simple class name — works everywhere, opens the first matching file.
 *
 * When neither strategy yields anything the link is rendered but a no-op on click — better than
 * a dead label.
 */
object FqnLink {

    private val SUPPORTED_EXTENSIONS = listOf("kt", "java", "groovy", "scala")

    fun render(project: Project?, fqn: String?): JComponent {
        val text = fqn?.takeIf { it.isNotBlank() } ?: return JBLabel(DASH)
        if (project == null) return JBLabel(text)
        return actionLink(text) { navigateTo(project, text) }
    }

    /** Plain label variant when the link semantics don't apply (e.g. unresolved value). */
    fun text(s: String?): JComponent = JBLabel(s.orDash())

    private fun navigateTo(project: Project, fqn: String) {
        // PSI / FilenameIndex lookups require a read action even when invoked from the EDT.
        val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
            tryFindByPsiFacade(project, fqn) ?: tryFindBySimpleName(project, fqn)
        }
        psiFile?.virtualFile?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    /**
     * Resolves via JavaPsiFacade by reflection so this class stays loadable when
     * com.intellij.modules.java is absent. Catches ClassNotFoundException at the boundary —
     * other exceptions (LinkageError, IndexOutOfBoundsException, etc.) bubble up and are
     * caught at the navigateTo level via the [SUPPORTED_EXTENSIONS] fallback below.
     */
    private fun tryFindByPsiFacade(project: Project, fqn: String): PsiFile? {
        val facadeClass = try {
            Class.forName("com.intellij.psi.JavaPsiFacade")
        } catch (_: ClassNotFoundException) {
            return null
        }
        return runCatching {
            val facade = facadeClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val findClass = facadeClass.getMethod(
                "findClass", String::class.java, GlobalSearchScope::class.java
            )
            val psiClass = findClass.invoke(facade, fqn, GlobalSearchScope.allScope(project))
                ?: return@runCatching null
            val nav = psiClass.javaClass.getMethod("getNavigationElement").invoke(psiClass)
            nav.javaClass.methods
                .firstOrNull { it.name == "getContainingFile" }
                ?.invoke(nav) as? PsiFile
        }.getOrNull()
    }

    private fun tryFindBySimpleName(project: Project, fqn: String): PsiFile? {
        val simple = fqn.substringAfterLast('.').substringAfterLast('$')
        if (simple.isBlank()) return null
        val scope = GlobalSearchScope.allScope(project)
        return SUPPORTED_EXTENSIONS
            .asSequence()
            .flatMap { FilenameIndex.getVirtualFilesByName("$simple.$it", scope).asSequence() }
            .firstNotNullOfOrNull { PsiManager.getInstance(project).findFile(it) }
    }
}
