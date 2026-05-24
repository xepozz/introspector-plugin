package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.GotoImplementationResponse
import com.github.xepozz.ide.introspector.model.HierarchyClassRef
import com.github.xepozz.ide.introspector.model.HierarchyNode
import com.github.xepozz.ide.introspector.model.ImplementationInfo
import com.github.xepozz.ide.introspector.model.ImplementationTarget
import com.github.xepozz.ide.introspector.model.TypeHierarchyResponse
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Backing engine for `psi.type_hierarchy` and `psi.goto_implementation`.
 *
 * Mirrors what IntelliJ's Hierarchy tool window (Ctrl+H) and Goto Implementation
 * action (Ctrl+Alt+B) do:
 *   - `ClassInheritorsSearch(checkDeep=false)` for subtype walks (recursed manually
 *     so we can bound depth and node count).
 *   - `PsiClass.supers` for the supertype walk.
 *   - `OverridingMethodsSearch(checkDeep=true)` for method overrides.
 *   - `DefinitionsScopedSearch` for class implementors / extenders (covers Kotlin
 *     `KtClass` via the platform's KtLightClass adapter without a Kotlin link-time dep).
 *
 * Threading: caller MUST hold a read action. PCE checks inside the per-result
 * processor stop the moment a cap trips. The 10 s `readActionBlocking` cap aborts
 * runaway library searches.
 *
 * Hot paths:
 *   - `java.lang.Object` subtype walk is always rejected (would be the world).
 *   - `scope="all"` always appends a warning so the agent learns to narrow on
 *     follow-ups.
 *   - `maxDepth` / `maxNodes` / `maxResults` provide hard, defensible bounds.
 */
object PsiHierarchyResolver {

    private const val OBJECT_FQN = "java.lang.Object"

    class NoTargetException(message: String) : RuntimeException(message)

    // ============================================================================
    // psi.type_hierarchy
    // ============================================================================

    fun typeHierarchy(
        project: Project,
        target: String?,
        psiFile: PsiFile?,
        offset: Int?,
        direction: String,
        scopeKind: String,
        maxDepth: Int,
        maxNodes: Int,
    ): TypeHierarchyResponse {
        val warnings = ArrayList<String>()
        val psiClass = resolveClassTarget(project, target, psiFile, offset)
        val classRef = describeClass(psiClass)
        val effectiveScope = globalScope(project, scopeKind)
        if (scopeKind == "all") {
            warnings += "scope=\"all\" includes library sources — searches on hot types " +
                "(java.util.List, java.lang.Object) can saturate the 10s read-action timeout. " +
                "Narrow to scope=\"project\" if results time out."
        }
        if (classRef.isSealed) {
            warnings += "Target is sealed — direct subtypes are exhaustive."
        }

        val budget = NodeBudget(maxNodes)
        budget.consume() // root counts

        var supertypes: HierarchyNode? = null
        var subtypes: HierarchyNode? = null

        if (direction == "up" || direction == "both") {
            supertypes = walkSupertypes(psiClass, maxDepth, budget)
        }
        if (direction == "down" || direction == "both") {
            if (psiClass.qualifiedName == OBJECT_FQN) {
                warnings += "Subtype walk for java.lang.Object is rejected — every class would be a child."
                subtypes = HierarchyNode(node = classRef, children = emptyList(), childrenTruncated = true)
            } else {
                subtypes = walkSubtypes(psiClass, effectiveScope, maxDepth, budget)
            }
        }

        return TypeHierarchyResponse(
            target = classRef,
            supertypes = supertypes,
            subtypes = subtypes,
            direction = direction,
            scope = scopeKind,
            truncated = budget.truncated,
            warnings = warnings,
        )
    }

    private fun walkSupertypes(root: PsiClass, maxDepth: Int, budget: NodeBudget): HierarchyNode {
        val seen = HashSet<String>()
        seen += root.qualifiedName ?: root.name ?: "(anonymous)"

        fun build(cls: PsiClass, depth: Int): HierarchyNode {
            val ref = describeClass(cls)
            if (budget.exhausted) return HierarchyNode(ref, emptyList(), childrenTruncated = true)
            if (depth >= maxDepth) {
                val parents = cls.supers.toList()
                return HierarchyNode(ref, emptyList(), childrenTruncated = parents.isNotEmpty())
            }
            val parents = cls.supers.toList()
            val children = ArrayList<HierarchyNode>()
            var cutByBudget = false
            for (parent in parents) {
                if (budget.exhausted) { cutByBudget = true; break }
                val key = parent.qualifiedName ?: parent.name ?: continue
                if (!seen.add(key)) continue
                budget.consume()
                children += build(parent, depth + 1)
            }
            return HierarchyNode(ref, children, childrenTruncated = cutByBudget)
        }

        return build(root, 0)
    }

    private fun walkSubtypes(
        root: PsiClass,
        scope: GlobalSearchScope,
        maxDepth: Int,
        budget: NodeBudget,
    ): HierarchyNode {
        val rootRef = describeClass(root)

        fun build(cls: PsiClass, depth: Int): HierarchyNode {
            val ref = if (cls === root) rootRef else describeClass(cls)
            if (budget.exhausted) return HierarchyNode(ref, emptyList(), childrenTruncated = true)
            if (depth >= maxDepth) {
                val anyChild = hasAnyDirectInheritor(cls, scope)
                return HierarchyNode(ref, emptyList(), childrenTruncated = anyChild)
            }
            // Only proceed if the class is extendable (not final). Anonymous / local
            // classes are filtered out at the description site below.
            if (cls.hasModifierProperty("final") && !cls.isInterface) {
                return HierarchyNode(ref, emptyList(), childrenTruncated = false)
            }
            val direct = ArrayList<PsiClass>()
            var cutByBudget = false
            try {
                ClassInheritorsSearch.search(cls, scope, /*checkDeep=*/false).forEach(Processor { sub ->
                    if (sub.qualifiedName == null) return@Processor true   // skip anonymous / local
                    if (budget.exhausted) {
                        cutByBudget = true
                        return@Processor false
                    }
                    budget.consume()
                    direct += sub
                    true
                })
            } catch (pce: ProcessCanceledException) {
                throw pce
            } catch (_: Throwable) {
                // Index hiccup — keep what we have.
            }
            val children = direct.map { build(it, depth + 1) }
            return HierarchyNode(ref, children, childrenTruncated = cutByBudget)
        }

        return build(root, 0)
    }

    private fun hasAnyDirectInheritor(cls: PsiClass, scope: GlobalSearchScope): Boolean {
        var found = false
        try {
            ClassInheritorsSearch.search(cls, scope, /*checkDeep=*/false).forEach(Processor { _ ->
                found = true
                false   // stop after the first
            })
        } catch (_: Throwable) {
            // best-effort: leave found=false
        }
        return found
    }

    // ============================================================================
    // psi.goto_implementation
    // ============================================================================

    fun gotoImplementation(
        project: Project,
        psiFile: PsiFile,
        offset: Int,
        scopeKind: String,
        maxResults: Int,
    ): GotoImplementationResponse {
        val warnings = ArrayList<String>()
        val target = resolveClassOrMethodTarget(psiFile, offset)
        val effectiveScope = globalScope(project, scopeKind)
        if (scopeKind == "all") {
            warnings += "scope=\"all\" includes library sources — searches on hot symbols can " +
                "saturate the 10s read-action timeout. Narrow to scope=\"project\" if results time out."
        }

        val out = ArrayList<ImplementationInfo>(maxResults.coerceAtMost(64))
        var truncated = false

        when (target) {
            is PsiMethod -> {
                if (target.containingClass?.qualifiedName == OBJECT_FQN) {
                    warnings += "Method belongs to java.lang.Object — overrides are world-wide; consider scope=\"project\"."
                }
                try {
                    OverridingMethodsSearch.search(target, effectiveScope, /*checkDeep=*/true)
                        .forEach(Processor { method ->
                            if (out.size >= maxResults) {
                                truncated = true
                                return@Processor false
                            }
                            if (method === target) return@Processor true
                            val info = describeMethodImpl(method)
                            if (info != null) out += info
                            true
                        })
                } catch (pce: ProcessCanceledException) {
                    throw pce
                } catch (_: Throwable) {
                }
            }
            is PsiClass -> {
                if (target.qualifiedName == OBJECT_FQN) {
                    warnings += "Class is java.lang.Object — implementors are world-wide; results capped or rejected. " +
                        "Pick a more specific class."
                    return GotoImplementationResponse(
                        target = describeImplTarget(target),
                        implementations = emptyList(),
                        scope = scopeKind,
                        total = 0,
                        truncated = true,
                        warnings = warnings,
                    )
                }
                try {
                    DefinitionsScopedSearch.search(target, effectiveScope, /*checkDeep=*/true)
                        .forEach(Processor { impl ->
                            if (out.size >= maxResults) {
                                truncated = true
                                return@Processor false
                            }
                            if (impl === target) return@Processor true
                            val info = describeClassImpl(impl)
                            if (info != null) out += info
                            true
                        })
                } catch (pce: ProcessCanceledException) {
                    throw pce
                } catch (_: Throwable) {
                }
            }
            else -> {
                throw NoTargetException(
                    "Caret is not on a class or method declaration / reference."
                )
            }
        }

        val sorted = out.sortedWith(
            compareBy({ it.fileUrl }, { it.range.startOffset })
        )
        return GotoImplementationResponse(
            target = describeImplTarget(target),
            implementations = sorted,
            scope = scopeKind,
            total = sorted.size,
            truncated = truncated,
            warnings = warnings,
        )
    }

    // ============================================================================
    // Target resolution
    // ============================================================================

    fun resolveClassTarget(
        project: Project,
        fqn: String?,
        psiFile: PsiFile?,
        offset: Int?,
    ): PsiClass {
        if (!fqn.isNullOrBlank()) {
            val facade = try {
                JavaPsiFacade.getInstance(project)
            } catch (_: NoClassDefFoundError) {
                throw NoTargetException("JavaPsiFacade is unavailable — com.intellij.modules.java is not loaded in this IDE.")
            } catch (_: ClassNotFoundException) {
                throw NoTargetException("JavaPsiFacade is unavailable — com.intellij.modules.java is not loaded in this IDE.")
            }
            val cls = facade.findClass(fqn, GlobalSearchScope.allScope(project))
                ?: throw NoTargetException("No class found for FQN: $fqn")
            return cls
        }
        require(psiFile != null && offset != null) {
            "psi.type_hierarchy requires either `target` (FQN) or fileUrl + offset/line+column."
        }
        return resolveClassAtOffset(psiFile, offset)
            ?: throw NoTargetException(
                "No class declaration / reference at offset $offset in ${psiFile.virtualFile?.url}."
            )
    }

    private fun resolveClassAtOffset(psiFile: PsiFile, offset: Int): PsiClass? {
        // Follow a reference if present — Ctrl-click semantics.
        val ref = psiFile.findReferenceAt(offset)
        val resolved = ref?.resolve()
        if (resolved is PsiClass) return resolved
        if (resolved != null) {
            // If we resolved to a method/field/etc., walk up to find the containing class.
            val containingClass = PsiTreeUtil.getParentOfType(resolved, PsiClass::class.java, false)
            if (containingClass != null) return containingClass
        }
        // Otherwise treat the nearest enclosing class declaration as the target.
        val leaf = psiFile.findElementAt(offset) ?: return null
        return PsiTreeUtil.getNonStrictParentOfType(leaf, PsiClass::class.java)
    }

    private fun resolveClassOrMethodTarget(psiFile: PsiFile, offset: Int): PsiElement? {
        val ref = psiFile.findReferenceAt(offset)
        val resolved = ref?.resolve()
        if (resolved is PsiMethod) return resolved
        if (resolved is PsiClass) return resolved
        val leaf = psiFile.findElementAt(offset) ?: return null
        // Prefer the nearest enclosing method, fall back to the nearest enclosing class.
        val method = PsiTreeUtil.getNonStrictParentOfType(leaf, PsiMethod::class.java)
        if (method != null) return method
        return PsiTreeUtil.getNonStrictParentOfType(leaf, PsiClass::class.java)
    }

    // ============================================================================
    // Description helpers
    // ============================================================================

    fun describeClass(cls: PsiClass): HierarchyClassRef {
        val vf = cls.containingFile?.virtualFile
        val nameRange = (cls.nameIdentifier?.textRange ?: cls.textRange) ?: TextRange(0, 0)
        val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
            ?: cls.containingFile?.viewProvider?.document
        return HierarchyClassRef(
            fqn = cls.qualifiedName,
            psiClass = cls.javaClass.simpleName.ifEmpty { cls.javaClass.name },
            fileUrl = vf?.url,
            declarationRange = PsiStructureWalker.textRangeInfoOf(nameRange, doc),
            isInterface = cls.isInterface,
            isAbstract = cls.hasModifierProperty("abstract"),
            isFinal = cls.hasModifierProperty("final"),
            isSealed = isSealedClass(cls),
            modifiers = PsiModifiers.read(cls.modifierList, PsiModifiers.CLASS),
        )
    }

    fun describeImplTarget(target: PsiElement): ImplementationTarget {
        return when (target) {
            is PsiClass -> {
                val vf = target.containingFile?.virtualFile
                val r = (target.nameIdentifier?.textRange ?: target.textRange) ?: TextRange(0, 0)
                val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
                    ?: target.containingFile?.viewProvider?.document
                ImplementationTarget(
                    name = target.name,
                    psiClass = target.javaClass.simpleName.ifEmpty { target.javaClass.name },
                    kind = "class",
                    fileUrl = vf?.url,
                    declarationRange = PsiStructureWalker.textRangeInfoOf(r, doc),
                    isAbstract = target.hasModifierProperty("abstract"),
                    isInterface = target.isInterface,
                )
            }
            is PsiMethod -> {
                val vf = target.containingFile?.virtualFile
                val r = (target.nameIdentifier?.textRange ?: target.textRange) ?: TextRange(0, 0)
                val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
                    ?: target.containingFile?.viewProvider?.document
                ImplementationTarget(
                    name = target.name,
                    psiClass = target.javaClass.simpleName.ifEmpty { target.javaClass.name },
                    kind = "method",
                    fileUrl = vf?.url,
                    declarationRange = PsiStructureWalker.textRangeInfoOf(r, doc),
                    isAbstract = target.hasModifierProperty("abstract"),
                    isInterface = target.containingClass?.isInterface == true,
                )
            }
            else -> ImplementationTarget(
                name = (target as? PsiNamedElement)?.name,
                psiClass = target.javaClass.simpleName.ifEmpty { target.javaClass.name },
                kind = "class",
            )
        }
    }

    private fun describeMethodImpl(method: PsiMethod): ImplementationInfo? {
        val file = method.containingFile ?: return null
        val vf = file.virtualFile ?: return null
        val doc: Document? = file.viewProvider.document
            ?: FileDocumentManager.getInstance().getDocument(vf)
        val nameRange = (method.nameIdentifier?.textRange ?: method.textRange) ?: TextRange(0, 0)
        val isAbstract = method.hasModifierProperty("abstract")
        return ImplementationInfo(
            fileUrl = vf.url,
            range = PsiStructureWalker.textRangeInfoOf(nameRange, doc),
            lineSnippet = lineSnippetOf(doc, nameRange.startOffset, 120),
            declaringClassFqn = method.containingClass?.qualifiedName,
            signature = methodSignature(method),
            isAbstract = isAbstract,
            isOverride = !isAbstract,
        )
    }

    private fun describeClassImpl(impl: PsiElement): ImplementationInfo? {
        val cls = impl as? PsiClass ?: PsiTreeUtil.getParentOfType(impl, PsiClass::class.java, false)
            ?: return null
        if (cls.qualifiedName == null) return null   // skip anonymous / local
        val file = cls.containingFile ?: return null
        val vf = file.virtualFile ?: return null
        val doc: Document? = file.viewProvider.document
            ?: FileDocumentManager.getInstance().getDocument(vf)
        val nameRange = (cls.nameIdentifier?.textRange ?: cls.textRange) ?: TextRange(0, 0)
        return ImplementationInfo(
            fileUrl = vf.url,
            range = PsiStructureWalker.textRangeInfoOf(nameRange, doc),
            lineSnippet = lineSnippetOf(doc, nameRange.startOffset, 120),
            declaringClassFqn = cls.qualifiedName,
            signature = null,
            isAbstract = cls.hasModifierProperty("abstract"),
            isOverride = false,
        )
    }

    /**
     * Render an erasure-form method signature using the *overrider's* declared types.
     * No cross-boundary generic unification — what you see in the override site is what
     * you get. Format: `"<ReturnType> <name>(<P1Type> <p1>, <P2Type> <p2>)"`.
     */
    fun methodSignature(method: PsiMethod): String {
        val rt = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            val t = p.type.presentableText
            val n = p.name
            "$t $n"
        }
        return "$rt ${method.name}($params)"
    }

    // ============================================================================
    // Misc helpers
    // ============================================================================

    /**
     * Detect sealed types:
     *   - Java 17+ sealed classes / interfaces via `PsiClass.hasModifierProperty("sealed")`.
     *   - Kotlin sealed via simple-name probe on `KtClass` looking up `SEALED_KEYWORD`.
     *     Done by simple-name to avoid a Kotlin link-time dep (matches the convention used
     *     in `PsiUsageSearcher.isLocalVariableLike`).
     */
    private fun isSealedClass(cls: PsiClass): Boolean {
        // Java 17+ sealed
        if (runCatching { cls.hasModifierProperty("sealed") }.getOrDefault(false)) return true
        // Kotlin: KtLightClass wraps a KtClass; probe the navigation element.
        val nav = runCatching { cls.navigationElement }.getOrNull() ?: cls
        val cn = nav.javaClass.simpleName
        if (cn == "KtClass" || cn == "KtObjectDeclaration") {
            // Use reflection to call hasModifier(KtTokens.SEALED_KEYWORD) without linking Kotlin PSI.
            return runCatching {
                val text = nav.text ?: ""
                // Look at the first 40 chars of the declaration — enough to find the modifier
                // before the type name; avoids false positives inside the body.
                val head = if (text.length > 80) text.substring(0, 80) else text
                Regex("\\bsealed\\b").containsMatchIn(head)
            }.getOrDefault(false)
        }
        return false
    }

    private fun globalScope(project: Project, kind: String): GlobalSearchScope = when (kind) {
        "file" -> {
            // file-scope hierarchy is rarely useful; project semantics fall back to projectScope
            // when there's no anchor file — handled by callers that pass a PsiFile in.
            GlobalSearchScope.projectScope(project)
        }
        "all"  -> GlobalSearchScope.allScope(project)
        else   -> GlobalSearchScope.projectScope(project)
    }

    fun searchScopeForFile(file: PsiFile?, scopeKind: String, project: Project): SearchScope {
        if (scopeKind == "file" && file != null) {
            return GlobalSearchScope.fileScope(file)
        }
        return globalScope(project, scopeKind)
    }

    private fun lineSnippetOf(document: Document?, offset: Int, max: Int): String {
        if (document == null) return ""
        val capped = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(capped)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val raw = document.getText(TextRange(start, end)).trim()
        return if (raw.length <= max) raw else raw.substring(0, max) + "…"
    }

    // ----------------------------------------------------------------------------

    /**
     * Mutable shared budget for the type-hierarchy walks. Calling [consume] decrements
     * the budget; [exhausted] flips true when no headroom remains, and [truncated]
     * stays true thereafter for the response field.
     */
    class NodeBudget(private val maxNodes: Int) {
        private var remaining: Int = maxNodes
        var truncated: Boolean = false
            private set

        val exhausted: Boolean
            get() = remaining <= 0

        fun consume() {
            if (remaining <= 0) {
                truncated = true
                return
            }
            remaining--
            if (remaining <= 0) truncated = true
        }
    }
}
