package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.PsiFileTree
import com.github.xepozz.ide.introspector.model.PsiInjectionTree
import com.github.xepozz.ide.introspector.model.PsiNode
import com.github.xepozz.ide.introspector.model.TextRangeInfo
import com.intellij.lang.ASTNode
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.SourceTreeToPsiMap

/**
 * Pure (no-EDT, but caller must hold a read action) PSI walker shared by `psi.get_structure` and
 * `psi.get_references`. Mirrors `com.intellij.dev.psiViewer.ViewerTreeStructure.getChildElements`
 * — same AST-traversal model so the resulting tree matches what the IDE's bundled PSI Viewer
 * shows in the dev plugin.
 *
 * Key choices, copied from PsiViewer so the agent sees the same tree:
 *  - children come from `ASTNode.firstChildNode → treeNext` (gets pseudo-elements and tokens),
 *    not `PsiElement.getChildren()` (which silently drops whitespace and leaf tokens)
 *  - whitespace is filtered by `TokenType.WHITE_SPACE` element type — visible whitespace inside
 *    a string literal is not a TokenType.WHITE_SPACE token and survives the filter
 *  - injections are enumerated lazily via [InjectedLanguageManager.enumerate], deduplicated by
 *    (host, injectedPsi) tuple — the platform may call back multiple times for one place.
 *
 * Stable node ids:
 *  - root nodes for each view-provider PsiFile get id "<rootIdx>" (0, 1, …)
 *  - child of "0" at child-index 3 gets "0.3", at 17 gets "0.17", etc.
 *
 * That id is stable across calls as long as the file's PSI didn't change — both
 * `psi.get_structure` and `psi.get_references` produce the same ids, so the agent can cross-reference.
 */
object PsiStructureWalker {

    data class Result(
        val files: List<PsiFileTree>,
        val injections: List<PsiInjectionTree>,
        val truncated: Boolean,
        val nodeCount: Int,
        /** id of each PsiElement we visited, keyed by identity — used by the references walker. */
        val elementIds: Map<PsiElement, String>,
    )

    /**
     * Walk [rootFile]'s FileViewProvider (all language roots) and optionally enumerate injected
     * languages anchored on visited elements. Stops cleanly at [maxNodes] / [maxDepth].
     */
    fun walk(
        project: Project,
        rootFile: PsiFile,
        hostDocument: Document?,
        maxDepth: Int,
        maxNodes: Int,
        includeWhitespace: Boolean,
        includeText: Boolean,
        truncateNodeTextAt: Int,
        includeInjections: Boolean,
    ): Result {
        val viewProviderFiles = rootFile.viewProvider.allFiles
        val files = ArrayList<PsiFileTree>(viewProviderFiles.size)
        val injectionHosts = ArrayList<PsiLanguageInjectionHost>()
        val elementIds = HashMap<PsiElement, String>()

        var totalNodes = 0
        var overallTruncated = false

        for ((rootIdx, file) in viewProviderFiles.withIndex()) {
            val remaining = maxNodes - totalNodes
            if (remaining <= 0) {
                overallTruncated = true
                break
            }
            val singleTree = walkOne(
                rootId = rootIdx.toString(),
                root = file,
                document = hostDocument,
                maxDepth = maxDepth,
                budget = remaining,
                includeWhitespace = includeWhitespace,
                includeText = includeText,
                truncateNodeTextAt = truncateNodeTextAt,
                collectInjectionHosts = includeInjections,
                injectionHostsOut = injectionHosts,
                elementIdsOut = elementIds,
            )
            files += PsiFileTree(
                language = file.language.id,
                psiFileClass = file.javaClass.name,
                nodes = singleTree.nodes,
                truncated = singleTree.truncated,
            )
            totalNodes += singleTree.nodes.size
            if (singleTree.truncated) overallTruncated = true
        }

        val injections = if (includeInjections && totalNodes < maxNodes && injectionHosts.isNotEmpty())
            collectInjections(
                project = project,
                hosts = injectionHosts,
                hostDocument = hostDocument,
                budget = maxNodes - totalNodes,
                maxDepth = maxDepth,
                includeWhitespace = includeWhitespace,
                includeText = includeText,
                truncateNodeTextAt = truncateNodeTextAt,
                elementIdsOut = elementIds,
            ).also { totalNodes += it.sumOf { inj -> inj.tree.nodes.size } }
        else emptyList()

        return Result(files, injections, overallTruncated || totalNodes >= maxNodes, totalNodes, elementIds)
    }

    private data class SingleResult(val nodes: List<PsiNode>, val truncated: Boolean)

    private fun walkOne(
        rootId: String,
        root: PsiElement,
        document: Document?,
        maxDepth: Int,
        budget: Int,
        includeWhitespace: Boolean,
        includeText: Boolean,
        truncateNodeTextAt: Int,
        collectInjectionHosts: Boolean,
        injectionHostsOut: MutableList<PsiLanguageInjectionHost>,
        elementIdsOut: MutableMap<PsiElement, String>,
    ): SingleResult {
        val out = ArrayList<PsiNode>(64)
        // Iterative DFS so we don't blow the JVM stack on deep grammars (Kotlin call chains,
        // template strings). Frames track (element, parentId, depth, childIndex in parent's id).
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, null, 0, rootId))

        var truncated = false
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (out.size >= budget) {
                truncated = true
                break
            }
            val element = frame.element
            val id = frame.id

            val childList = if (frame.depth < maxDepth) astChildren(element, includeWhitespace) else emptyList()
            val node = nodeOf(
                id = id,
                parentId = frame.parentId,
                element = element,
                document = document,
                includeText = includeText,
                truncateNodeTextAt = truncateNodeTextAt,
                childCount = childList.size,
            )
            out += node
            elementIdsOut[element] = id

            if (collectInjectionHosts && element is PsiLanguageInjectionHost && element.isValidHost) {
                injectionHostsOut += element
            }

            // Push children in reverse so they pop in source order.
            for (i in childList.indices.reversed()) {
                val child = childList[i]
                stack.addLast(Frame(child, id, frame.depth + 1, "$id.$i"))
            }
        }
        return SingleResult(out, truncated)
    }

    private data class Frame(val element: PsiElement, val parentId: String?, val depth: Int, val id: String)

    /**
     * Returns the AST-level children of [element], in source order, with whitespace optionally
     * filtered. Mirrors `ViewerTreeStructure.getChildElements` — we walk the AST so token leaves
     * (identifiers, punctuation) appear; `PsiElement.getChildren()` would drop them.
     */
    private fun astChildren(element: PsiElement, includeWhitespace: Boolean): List<PsiElement> {
        val root: ASTNode = SourceTreeToPsiMap.psiElementToTree(element) ?: return emptyList()
        val out = ArrayList<PsiElement>()
        var child = root.firstChildNode
        while (child != null) {
            if (includeWhitespace || child.elementType != TokenType.WHITE_SPACE) {
                val childPsi = child.psi
                if (childPsi != null) out += childPsi
            }
            child = child.treeNext
        }
        return out
    }

    private fun nodeOf(
        id: String,
        parentId: String?,
        element: PsiElement,
        document: Document?,
        includeText: Boolean,
        truncateNodeTextAt: Int,
        childCount: Int,
    ): PsiNode {
        val range = element.textRange
        val text = if (includeText && range != null) {
            val raw = element.text ?: ""
            if (raw.length > truncateNodeTextAt) raw.substring(0, truncateNodeTextAt) + "…" else raw
        } else null

        // PsiElement.references is the cheap path — provider lookup but no resolution.
        val hasRefs = try {
            element.references.isNotEmpty()
        } catch (_: Throwable) {
            false
        }

        return PsiNode(
            id = id,
            parentId = parentId,
            psiClass = element.javaClass.simpleName.ifEmpty { element.javaClass.name },
            elementType = element.node?.elementType?.toString() ?: "?",
            textRange = textRangeInfoOf(range, document),
            text = text,
            hasReferences = hasRefs,
            isInjectionHost = element is PsiLanguageInjectionHost && element.isValidHost,
            childCount = childCount,
        )
    }

    fun textRangeInfoOf(range: com.intellij.openapi.util.TextRange?, document: Document?): TextRangeInfo {
        if (range == null) return TextRangeInfo(0, 0, 1, 1, 1, 1)
        val start = range.startOffset
        val end = range.endOffset
        val (sl, sc) = lineCol(document, start)
        val (el, ec) = lineCol(document, end)
        return TextRangeInfo(start, end, sl, sc, el, ec)
    }

    private fun lineCol(document: Document?, offset: Int): Pair<Int, Int> {
        if (document == null) return 1 to 1
        val capped = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(capped)
        val col = capped - document.getLineStartOffset(line)
        return (line + 1) to (col + 1)
    }

    private fun collectInjections(
        project: Project,
        hosts: List<PsiLanguageInjectionHost>,
        hostDocument: Document?,
        budget: Int,
        maxDepth: Int,
        includeWhitespace: Boolean,
        includeText: Boolean,
        truncateNodeTextAt: Int,
        elementIdsOut: MutableMap<PsiElement, String>,
    ): List<PsiInjectionTree> {
        val mgr = InjectedLanguageManager.getInstance(project)
        val out = ArrayList<PsiInjectionTree>()
        var remaining = budget
        val seen = HashSet<Pair<PsiElement, PsiElement>>()
        for (host in hosts) {
            if (remaining <= 0) break
            mgr.enumerate(host) { injectedPsi, _ ->
                if (remaining <= 0) return@enumerate
                val key = host to injectedPsi
                if (!seen.add(key)) return@enumerate

                val hostRange = mgr.injectedToHost(injectedPsi, injectedPsi.textRange)
                val rootId = "inj_${out.size}"
                val tree = walkOne(
                    rootId = rootId,
                    root = injectedPsi,
                    // Injection nodes carry offsets in the INJECTED document. We deliberately
                    // pass null for line/column resolution; the consumer should rely on hostRange
                    // for cross-referencing into the source file.
                    document = null,
                    maxDepth = maxDepth,
                    budget = remaining,
                    includeWhitespace = includeWhitespace,
                    includeText = includeText,
                    truncateNodeTextAt = truncateNodeTextAt,
                    collectInjectionHosts = false,
                    injectionHostsOut = mutableListOf(),
                    elementIdsOut = elementIdsOut,
                )
                remaining -= tree.nodes.size

                out += PsiInjectionTree(
                    hostNodeId = elementIdsOut[host],
                    hostRange = textRangeInfoOf(hostRange, hostDocument),
                    tree = PsiFileTree(
                        language = injectedPsi.language.id,
                        psiFileClass = injectedPsi.javaClass.name,
                        nodes = tree.nodes,
                        truncated = tree.truncated,
                    ),
                )
            }
        }
        return out
    }
}
