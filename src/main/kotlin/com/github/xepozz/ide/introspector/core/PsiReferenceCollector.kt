package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.ResolveTarget
import com.github.xepozz.ide.introspector.model.ResolvedReference
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceService

/**
 * Reference enumeration + resolution. Logic ported directly from
 * `com.intellij.dev.psiViewer.PsiViewerDialog.doUpdateReferences` so the agent sees the same
 * resolution behaviour as IntelliJ's bundled PSI Viewer (Tools > View PSI Structure of Current File).
 *
 * Why `PsiReferenceService` and not `element.getReferences()` directly:
 *   The service merges in-tree references with the `psi.referenceContributor` extension point
 *   contributions (e.g. PHP injects extra references into string literals via this EP). Skipping
 *   it would miss any contributor that doesn't write its references back into the element.
 *
 * Caller MUST hold a platform read action (use `readActionBlocking { ... }`); we also wrap in
 * `DumbService` ignore so a background index update doesn't make calls fail with
 * `IndexNotReadyException` (same defence PsiViewer uses).
 */
object PsiReferenceCollector {

    /**
     * Walks every PsiElement of [rootFile]'s view-provider files, collecting references.
     * Truncates at [maxReferences] and reports the cap in the second component.
     */
    fun collectInFile(
        rootFile: PsiFile,
        hostDocument: Document?,
        maxReferences: Int,
        truncateTextAt: Int,
        includeMultiResolve: Boolean,
        elementIds: Map<PsiElement, String>,
    ): Pair<List<ResolvedReference>, Boolean> {
        val out = ArrayList<ResolvedReference>()
        var truncated = false
        for (file in rootFile.viewProvider.allFiles) {
            if (out.size >= maxReferences) {
                truncated = true
                break
            }
            file.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (out.size >= maxReferences) {
                        truncated = true
                        return
                    }
                    collectFromElement(
                        element = element,
                        hostDocument = hostDocument,
                        truncateTextAt = truncateTextAt,
                        includeMultiResolve = includeMultiResolve,
                        elementIds = elementIds,
                        out = out,
                    )
                    super.visitElement(element)
                }
            })
        }
        return out to truncated
    }

    /** Single-point variant — every reference attached to one specific element. */
    fun collectAtElement(
        element: PsiElement,
        hostDocument: Document?,
        truncateTextAt: Int,
        includeMultiResolve: Boolean,
        elementIds: Map<PsiElement, String>,
    ): List<ResolvedReference> {
        val out = ArrayList<ResolvedReference>()
        collectFromElement(element, hostDocument, truncateTextAt, includeMultiResolve, elementIds, out)
        return out
    }

    private fun collectFromElement(
        element: PsiElement,
        hostDocument: Document?,
        truncateTextAt: Int,
        includeMultiResolve: Boolean,
        elementIds: Map<PsiElement, String>,
        out: MutableList<ResolvedReference>,
    ) {
        val refs: List<PsiReference> = try {
            PsiReferenceService.getService().getReferences(element, PsiReferenceService.Hints.NO_HINTS)
        } catch (_: Throwable) {
            return
        }
        if (refs.isEmpty()) return

        val containingFile = element.containingFile
        val elementStart = element.textRange?.startOffset ?: return
        val elementId = elementIds[element]

        for (ref in refs) {
            val refRangeInElement: TextRange = try {
                ref.rangeInElement
            } catch (_: Throwable) {
                continue
            }
            val absStart = elementStart + refRangeInElement.startOffset
            val absEnd = elementStart + refRangeInElement.endOffset

            val sourceText = try {
                element.text?.let { full ->
                    val s = refRangeInElement.startOffset.coerceIn(0, full.length)
                    val e = refRangeInElement.endOffset.coerceIn(s, full.length)
                    full.substring(s, e)
                }.orEmpty()
            } catch (_: Throwable) {
                ""
            }

            val isSoft = runCatching { ref.isSoft }.getOrDefault(false)

            val targets: List<PsiElement?> = try {
                if (ref is PsiPolyVariantReference && includeMultiResolve) {
                    ref.multiResolve(true).map { it.element }
                } else {
                    listOfNotNull(ref.resolve())
                }
            } catch (_: Throwable) {
                emptyList()
            }

            out += ResolvedReference(
                sourceNodeId = elementId,
                sourcePsiClass = element.javaClass.simpleName.ifEmpty { element.javaClass.name },
                sourceText = truncate(sourceText, truncateTextAt),
                sourceRange = PsiStructureWalker.textRangeInfoOf(TextRange(absStart, absEnd), hostDocument),
                referenceClass = ref.javaClass.name,
                isSoft = isSoft,
                targets = targets.ifEmpty { listOf(null) }.map { describeTarget(it, containingFile, hostDocument, truncateTextAt) },
            )
        }
    }

    private fun describeTarget(
        target: PsiElement?,
        sourceContainingFile: PsiFile?,
        hostDocument: Document?,
        truncateTextAt: Int,
    ): ResolveTarget {
        if (target == null) {
            return ResolveTarget(resolved = false)
        }
        val targetFile = target.containingFile
        val targetVf = targetFile?.virtualFile
        val sameFile = sourceContainingFile != null && targetFile != null &&
            sourceContainingFile.viewProvider == targetFile.viewProvider

        val targetText = try {
            target.text?.let { truncate(it, truncateTextAt) }
        } catch (_: Throwable) {
            null
        }

        val rangeInfo = try {
            // Line/column meaningful only for same-file targets, where hostDocument applies.
            PsiStructureWalker.textRangeInfoOf(target.textRange, if (sameFile) hostDocument else null)
        } catch (_: Throwable) {
            null
        }

        return ResolveTarget(
            resolved = true,
            targetPsiClass = target.javaClass.simpleName.ifEmpty { target.javaClass.name },
            targetText = targetText,
            targetRange = rangeInfo,
            targetFileUrl = if (!sameFile) targetVf?.url else null,
            sameFile = sameFile,
            declarationName = (target as? PsiNamedElement)?.name,
        )
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "…"
}
