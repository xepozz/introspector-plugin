package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.FileUsages
import com.github.xepozz.ide.introspector.model.FindUsagesResponse
import com.github.xepozz.ide.introspector.model.TargetInfo
import com.github.xepozz.ide.introspector.model.UsageInfo
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Find Usages engine for `psi.find_usages`. Two-stage:
 *
 *  1. **Resolve the target.** Given a file + offset, pick the declaration the user really meant
 *     — the same logic IntelliJ uses when you press Find Usages on a usage site (follow the
 *     reference) vs. on the declaration itself (use it directly).
 *
 *  2. **Search.** Run [ReferencesSearch] for the target, plus optionally [DefinitionsScopedSearch]
 *     to fold in overriding methods / implementing classes (the same query behind Ctrl+Alt+B
 *     "Goto Implementation" — platform-level so it works in any language).
 *
 * Threading: caller MUST hold a read action. We rely on the platform's 10 s read-action timeout
 * (`readActionBlocking`) to abort runaway searches — `Query.forEach` checks PCE on each tick so
 * cancellation propagates cleanly.
 *
 * Hot keys for the agent:
 *  - Local variables get [LocalSearchScope] of their containing function automatically — any
 *    `scope="project"` / `scope="all"` requested for a local would be wasteful and also wrong
 *    (a local `i` in `foo()` has no meaning outside `foo()`).
 *  - Library searches (`scope="all"`) are gated by the 10 s timeout. For popular declarations
 *    inside a jar (`java.util.HashMap`, …) the agent should narrow to `scope="project"`.
 */
object PsiUsageSearcher {

    /**
     * Resolve "what declaration is at this offset?" — follow a reference under the caret if
     * present (Ctrl-click semantics), otherwise treat the nearest [PsiNamedElement] as the
     * declaration. Returns null when neither is present (caret on whitespace, comment, etc.).
     */
    fun resolveTarget(psiFile: PsiFile, offset: Int): PsiElement? {
        val project = psiFile.project
        // findReferenceAt + findElementAt are injection-aware via InjectedLanguageManager wrappers.
        // Use the host file here; the agent passes host-coordinate offsets.
        val ref = psiFile.findReferenceAt(offset)
        if (ref != null) {
            val resolved = if (ref is PsiPolyVariantReference)
                ref.multiResolve(true).firstOrNull()?.element
            else ref.resolve()
            if (resolved != null) return resolved
        }
        val leaf = InjectedLanguageManager.getInstance(project)
            .findInjectedElementAt(psiFile, offset) ?: psiFile.findElementAt(offset)
        // Walk up. Skip the leaf itself if it's e.g. a token — getNonStrictParentOfType finds
        // the nearest named ancestor (method name → method, class name → class).
        return PsiTreeUtil.getNonStrictParentOfType(leaf, PsiNamedElement::class.java)
    }

    fun findUsages(
        project: Project,
        psiFile: PsiFile,
        offset: Int,
        scopeKind: String,
        includeImplementations: Boolean,
        maxUsages: Int,
        truncateTextAt: Int,
        groupByFile: Boolean,
    ): FindUsagesResponse {
        val target = resolveTarget(psiFile, offset)
            ?: throw NoTargetException("No declaration / reference at offset $offset in ${psiFile.virtualFile?.url}.")

        val targetFile = target.containingFile ?: throw NoTargetException(
            "Resolved target has no containing file (likely synthetic / library stub)."
        )
        val targetDocument = targetFile.viewProvider.document
            ?: FileDocumentManager.getInstance().getDocument(targetFile.virtualFile)

        val effectiveScope = resolveScope(project, target, scopeKind)

        val out = ArrayList<UsageInfo>(maxUsages.coerceAtMost(64))
        var truncated = false

        // (a) ReferencesSearch — the canonical "usage of declaration" query. Processor returns
        // false to abort the stream — we abort the moment we hit maxUsages. (Query has both
        // forEach(Consumer):void and forEach(Processor):boolean overloads; the SAM wrapper
        // disambiguates.)
        try {
            ReferencesSearch.search(target, effectiveScope).forEach(Processor { ref ->
                if (out.size >= maxUsages) {
                    truncated = true
                    return@Processor false
                }
                val info = describeReferenceUsage(ref, truncateTextAt)
                if (info != null) out += info
                true
            })
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (_: Throwable) {
            // Index hiccup / cancelled — keep what we have. The 10 s timeout will surface
            // separately via TimeoutException if we're really stuck.
        }

        // (b) DefinitionsScopedSearch — overriding methods + implementing classes, language-agnostic.
        // Each hit here is a declaration (PsiNamedElement), not a reference — render its own range.
        if (includeImplementations && !truncated && out.size < maxUsages) {
            try {
                DefinitionsScopedSearch.search(target, effectiveScope, true).forEach(Processor { impl ->
                    if (out.size >= maxUsages) {
                        truncated = true
                        return@Processor false
                    }
                    if (impl === target) return@Processor true   // skip self
                    val info = describeImplementation(impl, truncateTextAt)
                    if (info != null) out += info
                    true
                })
            } catch (pce: ProcessCanceledException) {
                throw pce
            } catch (_: Throwable) {
            }
        }

        val targetInfo = describeTarget(target, targetDocument, truncateTextAt)

        return if (groupByFile) {
            val grouped = out.groupBy { it.fileUrl }.map { (url, list) ->
                FileUsages(fileUrl = url, fileType = list.first().fileType, usages = list)
            }.sortedBy { it.fileUrl }
            FindUsagesResponse(
                target = targetInfo,
                scope = scopeKind,
                usages = emptyList(),
                byFile = grouped,
                total = out.size,
                truncated = truncated,
            )
        } else {
            FindUsagesResponse(
                target = targetInfo,
                scope = scopeKind,
                usages = out,
                byFile = emptyList(),
                total = out.size,
                truncated = truncated,
            )
        }
    }

    /**
     * Locals & parameters: searching them across `project` / `all` scope is wasteful (their
     * binding can't leak past their containing file) and harms perf — narrow to the file.
     * Heuristic-based local detection so we stay lang-agnostic at link time (no PsiLocalVariable
     * / PsiParameter imports, which live in Java PSI).
     */
    private fun resolveScope(project: Project, target: PsiElement, kind: String): SearchScope {
        if (isLocalVariableLike(target)) {
            return LocalSearchScope(target.containingFile)
        }
        return when (kind) {
            "file" -> GlobalSearchScope.fileScope(target.containingFile)
            "all"  -> GlobalSearchScope.allScope(project)
            else   -> GlobalSearchScope.projectScope(project)   // "project" default
        }
    }

    private fun isLocalVariableLike(target: PsiElement): Boolean {
        val name = target.javaClass.simpleName
        // Java PsiLocalVariable / PsiParameter, Kotlin KtParameter / KtDestructuringDeclarationEntry,
        // JS/TS JSParameter, PHP Parameter — convention is stable enough across the IntelliJ
        // language plugins to use the simple name as a marker.
        return name == "PsiLocalVariable" || name == "PsiParameter" ||
            name == "KtParameter" || name == "KtDestructuringDeclarationEntry" ||
            name == "JSParameter"
    }

    // ---------- description / serialization ----------

    private fun describeTarget(target: PsiElement, document: Document?, truncateTextAt: Int): TargetInfo {
        val vf = target.containingFile?.virtualFile
        val range = target.textRange ?: TextRange(0, 0)
        // For the target preview, use the *first non-empty line* — methods/classes can span
        // hundreds of lines and the full text is useless without a viewer.
        val rawText = target.text ?: ""
        val firstLine = rawText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return TargetInfo(
            psiClass = target.javaClass.simpleName.ifEmpty { target.javaClass.name },
            declarationName = (target as? PsiNamedElement)?.name,
            fileUrl = vf?.url ?: "",
            range = PsiStructureWalker.textRangeInfoOf(range, document),
            text = truncate(firstLine, truncateTextAt),
        )
    }

    private fun describeReferenceUsage(
        ref: com.intellij.psi.PsiReference,
        truncateTextAt: Int,
    ): UsageInfo? {
        val element = ref.element
        val file = element.containingFile ?: return null
        val vf = file.virtualFile ?: return null
        val document = file.viewProvider.document
            ?: FileDocumentManager.getInstance().getDocument(vf)
            ?: return null

        val elemRange = element.textRange ?: return null
        val rangeIn = try { ref.rangeInElement } catch (_: Throwable) { return null }
        val absStart = elemRange.startOffset + rangeIn.startOffset
        val absEnd = elemRange.startOffset + rangeIn.endOffset

        val refText = try {
            val full = element.text ?: ""
            val s = rangeIn.startOffset.coerceIn(0, full.length)
            val e = rangeIn.endOffset.coerceIn(s, full.length)
            full.substring(s, e)
        } catch (_: Throwable) { "" }

        return UsageInfo(
            kind = "reference",
            fileUrl = vf.url,
            fileType = file.fileType.name,
            range = PsiStructureWalker.textRangeInfoOf(TextRange(absStart, absEnd), document),
            text = truncate(refText, truncateTextAt),
            lineSnippet = lineSnippetOf(document, absStart, truncateTextAt),
            referenceClass = ref.javaClass.name,
            isSoft = runCatching { ref.isSoft }.getOrDefault(false),
            containingDeclaration = enclosingDeclarationName(element),
        )
    }

    private fun describeImplementation(impl: PsiElement, truncateTextAt: Int): UsageInfo? {
        val file = impl.containingFile ?: return null
        val vf = file.virtualFile ?: return null
        val document = file.viewProvider.document
            ?: FileDocumentManager.getInstance().getDocument(vf)
            ?: return null
        val range = impl.textRange ?: return null
        val firstLine = (impl.text ?: "").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return UsageInfo(
            kind = "implementation",
            fileUrl = vf.url,
            fileType = file.fileType.name,
            range = PsiStructureWalker.textRangeInfoOf(range, document),
            text = truncate(firstLine, truncateTextAt),
            lineSnippet = lineSnippetOf(document, range.startOffset, truncateTextAt),
            referenceClass = null,
            isSoft = false,
            containingDeclaration = enclosingDeclarationName(impl),
        )
    }

    /**
     * The line of source on which [offset] sits, trimmed and truncated. Replicates the line-of-
     * context display in IntelliJ's Find Usages tool window — gives the agent enough surrounding
     * code to interpret the hit without a follow-up read.
     */
    private fun lineSnippetOf(document: Document, offset: Int, max: Int): String {
        val capped = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(capped)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val raw = document.getText(TextRange(start, end)).trim()
        return truncate(raw, max)
    }

    private fun enclosingDeclarationName(element: PsiElement): String? {
        var node: PsiElement? = element.parent
        while (node != null && node !is PsiFile) {
            if (node is PsiNamedElement) {
                val name = node.name
                if (!name.isNullOrBlank()) return "${node.javaClass.simpleName}:$name"
            }
            node = node.parent
        }
        return null
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "…"

    class NoTargetException(message: String) : RuntimeException(message)
}
