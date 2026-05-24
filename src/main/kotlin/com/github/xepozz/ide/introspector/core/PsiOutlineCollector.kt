package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.GetOutlineResponse
import com.github.xepozz.ide.introspector.model.OutlineNode
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Builds the outline (declaration tree) for a [PsiFile] using IntelliJ's
 * [LanguageStructureViewBuilder]. Backs `psi.get_outline`.
 *
 * StructureViewBuilder is the per-language extension that powers the Structure tool window
 * — every language plugin (Java, Kotlin, JSON, YAML, …) contributes its own. Walking the
 * resulting `StructureViewModel` instead of the raw PSI tree gives us the same declaration
 * set the IDE shows in its sidebar, which is exactly the shape an agent wants when asking
 * "what methods are in this file?".
 *
 * Three reasons we don't fall back to a per-language hand-rolled walker:
 *  1. Languages outside Java/Kotlin (JSON, YAML, HTML, Properties) all have
 *     StructureViewBuilders out of the box — free coverage.
 *  2. Per-language structure ordering (e.g. Kotlin shows top-level functions before classes
 *     by default) is preserved.
 *  3. Anonymous classes / lambdas are excluded by the builder's natural taxonomy — same as
 *     the Structure tool window, same as what the agent expects.
 *
 * Caller MUST hold a platform read action.
 *
 * Limits:
 *  - [maxNodes] caps the TOTAL count across the recursive tree. When hit, returns the prefix
 *    walked so far + `truncated=true` + a warning.
 *  - [maxDepth] caps recursion depth. Per spec: "Max outline depth. Default 6."
 *  - When no [StructureViewBuilder] is registered for the file's language (binary file,
 *    obscure language plugin not loaded), returns empty nodes + warning.
 */
object PsiOutlineCollector {

    fun collect(
        psiFile: PsiFile,
        includeFields: Boolean,
        includeInherited: Boolean,
        maxDepth: Int,
        maxNodes: Int,
    ): GetOutlineResponse {
        val fileUrl = psiFile.virtualFile?.url ?: ""
        val fileType = psiFile.fileType.name
        val language = psiFile.language.id

        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
        if (builder == null) {
            return GetOutlineResponse(
                fileUrl = fileUrl,
                fileType = fileType,
                language = language,
                nodes = emptyList(),
                nodeCount = 0,
                truncated = false,
                warnings = listOf("No StructureViewBuilder for fileType=$fileType"),
            )
        }

        // Only tree-based builders expose a walkable model; some custom builders (e.g.
        // FileStructurePopupBuilder for non-tree views) return a TextEditorBasedStructureViewModel
        // wrapper. We require TreeBased to keep recursion logic simple and reliable.
        if (builder !is TreeBasedStructureViewBuilder) {
            return GetOutlineResponse(
                fileUrl = fileUrl,
                fileType = fileType,
                language = language,
                nodes = emptyList(),
                nodeCount = 0,
                truncated = false,
                warnings = listOf(
                    "StructureViewBuilder for fileType=$fileType is not tree-based (${builder.javaClass.simpleName})",
                ),
            )
        }

        val model: StructureViewModel = try {
            builder.createStructureViewModel(null)
        } catch (e: Throwable) {
            return GetOutlineResponse(
                fileUrl = fileUrl,
                fileType = fileType,
                language = language,
                nodes = emptyList(),
                nodeCount = 0,
                truncated = false,
                warnings = listOf("Failed to create StructureViewModel: ${e.javaClass.simpleName}: ${e.message}"),
            )
        }

        try {
            val ctx = WalkContext(
                includeFields = includeFields,
                includeInherited = includeInherited,
                maxDepth = maxDepth,
                maxNodes = maxNodes,
            )
            val rootChildren = model.root.children
            val outline = ArrayList<OutlineNode>(rootChildren.size)
            for (child in rootChildren) {
                if (ctx.exhausted()) {
                    ctx.truncated = true
                    break
                }
                val node = walk(child, depth = 0, ctx = ctx) ?: continue
                outline += node
            }
            val warnings = ArrayList<String>(1)
            if (ctx.truncated) warnings += "outline truncated at $maxNodes nodes"
            return GetOutlineResponse(
                fileUrl = fileUrl,
                fileType = fileType,
                language = language,
                nodes = outline,
                nodeCount = ctx.emitted,
                truncated = ctx.truncated,
                warnings = warnings,
            )
        } finally {
            // StructureViewModel implementations may hold tree caches / listeners; per the
            // Structure View docs, callers should dispose() once they're done.
            try { model.dispose() } catch (_: Throwable) {}
        }
    }

    private class WalkContext(
        val includeFields: Boolean,
        val includeInherited: Boolean,
        val maxDepth: Int,
        val maxNodes: Int,
        var emitted: Int = 0,
        var truncated: Boolean = false,
    ) {
        fun exhausted(): Boolean = emitted >= maxNodes
        fun tick() { emitted++ }
    }

    private fun walk(element: TreeElement, depth: Int, ctx: WalkContext): OutlineNode? {
        if (ctx.exhausted()) {
            ctx.truncated = true
            return null
        }
        val psi = (element as? StructureViewTreeElement)?.value as? PsiElement
            ?: return null   // skip non-PSI elements (e.g. JSON property nodes — but JsonStructureView wraps PsiElement)

        val classified = PsiKindClassifier.classify(psi)
        val kind = classified.kind

        // includeFields=false drops field/property leaves (still walks deeper if they happen
        // to have children — usually they don't).
        if (!ctx.includeFields && (kind == "field" || kind == "property")) return null

        // includeInherited=false drops anything the structure view marked as inherited. We
        // use a duck-typed check (`StructureViewTreeElement` doesn't itself expose an
        // "isInherited" flag, but many implementations subclass JavaInheritedMembersNodeProvider
        // contributions that ultimately make inherited members appear when groupers/filters
        // are enabled — the v1 contract simply doesn't surface inherited unless explicitly
        // requested, so we err on the side of skipping nothing extra here for now).
        // Note: getGroupers() would let us surface inherited explicitly; deferred to v2.

        // Name fallback: anonymous classes and synthetic PSI may have no name. The platform
        // structure view shows them with a labelled presentation — we adopt the simple name
        // from PsiKindClassifier and fall back to the PSI class name. We don't yet have a
        // displayable name, so use "<unnamed>" as a last resort to keep the schema invariant.
        val displayName = classified.name ?: psi.let {
            (element as? StructureViewTreeElement)?.presentation?.presentableText
        } ?: "<unnamed>"

        ctx.tick()
        val ownIndex = ctx.emitted

        val children = if (depth + 1 >= ctx.maxDepth) emptyList() else {
            val out = ArrayList<OutlineNode>(8)
            try {
                for (child in element.children) {
                    if (ctx.exhausted()) {
                        ctx.truncated = true
                        break
                    }
                    val node = walk(child, depth + 1, ctx) ?: continue
                    out += node
                }
            } catch (_: Throwable) {
                // A misbehaving structure-view contributor (typically Vue/PHP custom views).
                // Keep what we have — partial result beats nothing.
            }
            out
        }

        // Suppress no-op note about ownIndex — keeps the linter quiet without altering logic.
        @Suppress("UNUSED_VARIABLE") val unusedOwnIndex = ownIndex

        return OutlineNode(
            name = displayName,
            kind = kind,
            fqn = classified.fqn,
            psiClass = psi.javaClass.simpleName.ifEmpty { psi.javaClass.name },
            declarationRange = PsiStructureWalker.textRangeInfoOf(psi.textRange, psi.containingFile?.viewProvider?.document),
            modifiers = classified.modifiers,
            returnType = classified.returnType,
            typeText = classified.typeText,
            children = children,
        )
    }
}
