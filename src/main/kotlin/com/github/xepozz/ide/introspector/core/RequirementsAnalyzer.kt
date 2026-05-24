package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.CallSiteAnalysis
import com.github.xepozz.ide.introspector.model.CheckRequirementsResponse
import com.github.xepozz.ide.introspector.model.RequirementAnnotation
import com.github.xepozz.ide.introspector.model.RequirementKind
import com.github.xepozz.ide.introspector.model.TargetInfo
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Static analysis engine for `arch.check_lock_requirements` and
 * `arch.check_threading_requirements` — see `docs/plans/arch-devkit-mirror.md`.
 *
 * Pipeline:
 *  1. Resolve the target method (FQN → JavaPsiFacade lookup, or position → containing method).
 *  2. Read the target's `com.intellij.util.concurrency.annotations.*` annotations.
 *  3. For each caller (ReferencesSearch + optional DefinitionsScopedSearch), walk to the
 *     enclosing method/lambda and decide whether it statically satisfies the contract by
 *     checking annotations + a small fixed set of recognised wrapper calls
 *     (ReadAction.run, invokeLater, executeOnPooledThread, …).
 *
 * Caller MUST hold a read action — same threading contract as [PsiUsageSearcher].
 *
 * Cross-language support: Java callers walk `PsiMethod` ancestry; Kotlin callers walk the
 * generic PsiElement ancestry and inspect annotations via simple-name matching on
 * KtAnnotationEntry (no hard Kotlin PSI dep — we duck-type the PSI tree).
 *
 * Cross-flavour support: this class assumes the Java module (`com.intellij.modules.java`)
 * is present — JavaPsiFacade is needed for FQN target resolution and PsiMethod for caller
 * walking. In IDEs without Java the `arch.check_*` tools will surface a clean
 * [JavaModuleUnavailable] error rather than crash.
 */
object RequirementsAnalyzer {

    // ---------- Annotation FQNs (hard-coded; see plan open Q #1) ----------

    const val FQN_REQUIRES_READ_LOCK = "com.intellij.util.concurrency.annotations.RequiresReadLock"
    const val FQN_REQUIRES_WRITE_LOCK = "com.intellij.util.concurrency.annotations.RequiresWriteLock"
    const val FQN_REQUIRES_READ_LOCK_ABSENCE = "com.intellij.util.concurrency.annotations.RequiresReadLockAbsence"
    const val FQN_REQUIRES_EDT = "com.intellij.util.concurrency.annotations.RequiresEdt"
    const val FQN_REQUIRES_BGT = "com.intellij.util.concurrency.annotations.RequiresBackgroundThread"
    const val FQN_REQUIRES_BLOCKING_CONTEXT = "com.intellij.util.concurrency.annotations.RequiresBlockingContext"

    val LOCK_ANNOTATION_FQNS: Set<String> = setOf(
        FQN_REQUIRES_READ_LOCK,
        FQN_REQUIRES_WRITE_LOCK,
        FQN_REQUIRES_READ_LOCK_ABSENCE,
    )

    val THREADING_ANNOTATION_FQNS: Set<String> = setOf(
        FQN_REQUIRES_EDT,
        FQN_REQUIRES_BGT,
        FQN_REQUIRES_BLOCKING_CONTEXT,
    )

    private val FQN_TO_KIND: Map<String, RequirementKind> = mapOf(
        FQN_REQUIRES_READ_LOCK to RequirementKind.READ_LOCK,
        FQN_REQUIRES_WRITE_LOCK to RequirementKind.WRITE_LOCK,
        FQN_REQUIRES_READ_LOCK_ABSENCE to RequirementKind.NO_READ_LOCK,
        FQN_REQUIRES_EDT to RequirementKind.EDT,
        FQN_REQUIRES_BGT to RequirementKind.BGT,
        FQN_REQUIRES_BLOCKING_CONTEXT to RequirementKind.BLOCKING_CONTEXT,
    )

    private val SIMPLE_TO_FQN: Map<String, String> = FQN_TO_KIND.keys.associateBy { it.substringAfterLast('.') }

    /** Recognised lock wrappers — fully-qualified call paths we match by callee FQN/simple name. */
    val LOCK_WRAPPERS: Set<String> = setOf(
        "com.intellij.openapi.application.ReadAction.run",
        "com.intellij.openapi.application.ReadAction.compute",
        "com.intellij.openapi.application.ReadAction.nonBlocking",
        "com.intellij.openapi.application.WriteAction.run",
        "com.intellij.openapi.application.WriteAction.compute",
        "com.intellij.openapi.application.ApplicationManager.Application.runReadAction",
        "com.intellij.openapi.application.ApplicationManager.Application.runWriteAction",
        // Kotlin top-level helpers
        "com.intellij.openapi.application.runReadAction",
        "com.intellij.openapi.application.runWriteAction",
    )

    val EDT_WRAPPERS: Set<String> = setOf(
        "com.intellij.openapi.application.ApplicationManager.Application.invokeLater",
        "com.intellij.openapi.application.ApplicationManager.Application.invokeAndWait",
        "javax.swing.SwingUtilities.invokeLater",
        "javax.swing.SwingUtilities.invokeAndWait",
        // Kotlin top-level helpers
        "com.intellij.openapi.application.invokeLater",
    )

    val BGT_WRAPPERS: Set<String> = setOf(
        "com.intellij.openapi.application.ApplicationManager.Application.executeOnPooledThread",
        "com.intellij.openapi.progress.ProgressManager.runProcessWithProgressAsynchronously",
    )

    // Match by simple name as a fallback when the callee can't be resolved to FQN — covers
    // the "Kotlin top-level extension function on Application" path where resolution may
    // return null without indices warmed up.
    private val LOCK_WRAPPER_SIMPLE = LOCK_WRAPPERS.map { it.substringAfterLast('.') }.toSet() +
        setOf("runReadAction", "runWriteAction", "computeWithAlternativeResolveEnabled")
    private val EDT_WRAPPER_SIMPLE = EDT_WRAPPERS.map { it.substringAfterLast('.') }.toSet()
    private val BGT_WRAPPER_SIMPLE = BGT_WRAPPERS.map { it.substringAfterLast('.') }.toSet()

    /** Thrown when the Java module is missing — surfaces as a clean MCP error from the toolset. */
    class JavaModuleUnavailable(message: String) : RuntimeException(message)

    /** Thrown when target resolution fails (unknown FQN, no method at offset). */
    class TargetNotFound(message: String) : RuntimeException(message)

    // ---------- public entry point ----------

    /**
     * Run the analysis against [annotationFqns] (either [LOCK_ANNOTATION_FQNS] for the lock
     * tool or [THREADING_ANNOTATION_FQNS] for the threading tool) and emit the response.
     *
     * Target resolution: exactly one of [target] OR ([psiFile] + [offset]) must be supplied —
     * the caller (ArchitectureToolset) is responsible for validating that.
     */
    fun analyze(
        project: Project,
        annotationFqns: Set<String>,
        wrapperKind: WrapperKind,
        target: String?,
        psiFile: PsiFile?,
        offset: Int?,
        scopeKind: String,
        includeImplementations: Boolean,
        maxCallSites: Int,
    ): CheckRequirementsResponse {
        val targets: List<PsiMethod> = when {
            target != null -> resolveByFqn(project, target)
            psiFile != null && offset != null -> resolveByPosition(psiFile, offset)
            else -> throw TargetNotFound("specify target OR fileUrl+position")
        }
        if (targets.isEmpty()) {
            throw TargetNotFound(
                if (target != null) "No method found for $target — checked allScope"
                else "No method at offset $offset in ${psiFile?.virtualFile?.url}"
            )
        }

        // Pick the first as the representative for TargetInfo (overload-ambiguous FQN search
        // may return >1; signature in response includes ALL).
        val primary = targets.first()
        val targetFile = primary.containingFile
            ?: throw TargetNotFound("Resolved target has no containing file (likely synthetic / library stub).")
        val targetDocument = targetFile.viewProvider.document
            ?: FileDocumentManager.getInstance().getDocument(targetFile.virtualFile)

        // Collect expected annotations from ALL overload candidates (a user-passed FQN like
        // "Foo.bar" gets every overload; if any carries an annotation, that's the contract
        // we check for callers of the same simple name).
        val expected = collectExpectedAnnotations(targets, annotationFqns)
        val targetInfo = describeTarget(primary, targetDocument)

        if (expected.isEmpty()) {
            return CheckRequirementsResponse(
                target = targetInfo,
                expected = emptyList(),
                callSites = emptyList(),
                total = 0,
                truncated = false,
            )
        }

        val effectiveScope = resolveScope(project, primary, scopeKind)
        val callSites = ArrayList<CallSiteAnalysis>(maxCallSites.coerceAtMost(64))
        var truncated = false
        val seen = HashSet<String>() // dedupe by (fileUrl, startOffset)

        for (tgt in targets) {
            if (truncated || callSites.size >= maxCallSites) break
            try {
                ReferencesSearch.search(tgt, effectiveScope).forEach(Processor { ref ->
                    if (callSites.size >= maxCallSites) {
                        truncated = true
                        return@Processor false
                    }
                    val analysis = analyzeCallSite(ref, expected, wrapperKind, annotationFqns) ?: return@Processor true
                    val key = "${analysis.fileUrl}:${analysis.range.startOffset}"
                    if (seen.add(key)) callSites += analysis
                    true
                })
            } catch (pce: ProcessCanceledException) {
                throw pce
            } catch (_: Throwable) {
                // Index hiccup — keep what we have.
            }
        }

        if (includeImplementations && !truncated && callSites.size < maxCallSites) {
            for (tgt in targets) {
                if (truncated || callSites.size >= maxCallSites) break
                try {
                    DefinitionsScopedSearch.search(tgt, effectiveScope, true).forEach(Processor { impl ->
                        if (callSites.size >= maxCallSites) {
                            truncated = true
                            return@Processor false
                        }
                        if (impl === tgt || impl !is PsiMethod) return@Processor true
                        try {
                            ReferencesSearch.search(impl, effectiveScope).forEach(Processor { ref ->
                                if (callSites.size >= maxCallSites) {
                                    truncated = true
                                    return@Processor false
                                }
                                val analysis = analyzeCallSite(ref, expected, wrapperKind, annotationFqns)
                                    ?: return@Processor true
                                val key = "${analysis.fileUrl}:${analysis.range.startOffset}"
                                if (seen.add(key)) callSites += analysis
                                true
                            })
                        } catch (pce: ProcessCanceledException) {
                            throw pce
                        } catch (_: Throwable) {
                            // continue
                        }
                        true
                    })
                } catch (pce: ProcessCanceledException) {
                    throw pce
                } catch (_: Throwable) {
                }
            }
        }

        return CheckRequirementsResponse(
            target = targetInfo,
            expected = expected,
            callSites = callSites,
            total = callSites.size,
            truncated = truncated,
        )
    }

    enum class WrapperKind { LOCK, THREADING }

    // ---------- target resolution ----------

    /**
     * Resolve a `FQN.method` string to every PsiMethod with that simple name on the class.
     * Overload-ambiguous — see plan.
     */
    private fun resolveByFqn(project: Project, target: String): List<PsiMethod> {
        val dot = target.lastIndexOf('.')
        if (dot <= 0 || dot == target.length - 1) {
            throw TargetNotFound("Invalid target '$target' — expected 'fully.qualified.Class.methodName'")
        }
        val classFqn = target.substring(0, dot)
        val methodName = target.substring(dot + 1)
        val facade = findJavaPsiFacade(project)
        val psiClass: PsiClass = facade.findClassByFqn(classFqn)
            ?: throw TargetNotFound("No class found for $classFqn — checked allScope")
        // The one-arg overload returns Array<JvmMethod>; the two-arg overload returns PsiMethod[]
        // which is what we want here. `checkBases = false` mirrors the plan (only declared methods).
        val methods = psiClass.findMethodsByName(methodName, false)
        if (methods.isEmpty()) {
            throw TargetNotFound("No method '$methodName' on $classFqn")
        }
        return methods.toList()
    }

    private fun resolveByPosition(psiFile: PsiFile, offset: Int): List<PsiMethod> {
        val leaf = psiFile.findElementAt(offset)
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, /* strict = */ false)
            ?: return emptyList()
        return listOf(method)
    }

    /**
     * JavaPsiFacade lives in com.intellij.modules.java. Wrap access so we fail with a clean
     * [JavaModuleUnavailable] error in IDEs without Java rather than NoClassDefFoundError.
     */
    private fun findJavaPsiFacade(project: Project): JavaPsiFacadeShim {
        return try {
            JavaPsiFacadeShim(com.intellij.psi.JavaPsiFacade.getInstance(project))
        } catch (e: NoClassDefFoundError) {
            throw JavaModuleUnavailable(
                "The Java module (com.intellij.modules.java) is not loaded in this IDE — " +
                    "arch.check_*_requirements requires it for FQN target resolution."
            )
        }
    }

    private class JavaPsiFacadeShim(private val facade: com.intellij.psi.JavaPsiFacade) {
        fun findClassByFqn(fqn: String): PsiClass? =
            facade.findClass(fqn, GlobalSearchScope.allScope(facade.project))
    }

    // ---------- expected annotations ----------

    private fun collectExpectedAnnotations(targets: List<PsiMethod>, allowed: Set<String>): List<RequirementAnnotation> {
        val out = LinkedHashSet<RequirementAnnotation>()
        for (t in targets) {
            for (ann in readAnnotations(t)) {
                if (ann.fqn in allowed) out += ann
            }
            // Method-level wins, but if the method has no relevant annotation, the containing
            // class's annotation applies (plan edge case 6).
            if (out.isEmpty()) {
                val owner = t.containingClass
                if (owner != null) {
                    for (ann in readAnnotations(owner)) {
                        if (ann.fqn in allowed) out += ann
                    }
                }
            }
        }
        return out.toList()
    }

    /** Read every `@Requires*` annotation present on the modifier-list owner. */
    fun readAnnotations(owner: PsiModifierListOwner): List<RequirementAnnotation> {
        val out = ArrayList<RequirementAnnotation>()
        val list = owner.modifierList ?: return out
        for (ann in list.annotations) {
            val fqn = ann.qualifiedName ?: continue
            val kind = FQN_TO_KIND[fqn] ?: continue
            out += RequirementAnnotation(kind, fqn)
        }
        return out
    }

    /** Like [readAnnotations] but on arbitrary PsiElement — duck-typed for Kotlin PSI. */
    private fun readAnnotationsAny(element: PsiElement?): List<RequirementAnnotation> {
        if (element == null) return emptyList()
        if (element is PsiModifierListOwner) return readAnnotations(element)
        // Kotlin: KtAnnotated has `annotationEntries: List<KtAnnotationEntry>`. We avoid a hard
        // dep by calling the property reflectively. Each entry's `shortName` gives us the simple
        // class name (resolution to FQN may need indices; the simple-name lookup is good enough).
        return runCatching {
            val mEntries = element.javaClass.methods.firstOrNull { it.name == "getAnnotationEntries" && it.parameterCount == 0 }
                ?: return@runCatching emptyList()
            @Suppress("UNCHECKED_CAST")
            val entries = mEntries.invoke(element) as? List<Any> ?: return@runCatching emptyList()
            val acc = ArrayList<RequirementAnnotation>()
            for (entry in entries) {
                val simple = simpleNameOfKtAnnotation(entry) ?: continue
                val fqn = SIMPLE_TO_FQN[simple] ?: continue
                val kind = FQN_TO_KIND[fqn] ?: continue
                acc += RequirementAnnotation(kind, fqn)
            }
            acc
        }.getOrDefault(emptyList())
    }

    private fun simpleNameOfKtAnnotation(entry: Any): String? {
        return runCatching {
            // KtAnnotationEntry.shortName: Name? (Kotlin Name; toString())
            val mShort = entry.javaClass.methods.firstOrNull { it.name == "getShortName" && it.parameterCount == 0 }
            val short = mShort?.invoke(entry)
            short?.toString()
        }.getOrNull()
    }

    // ---------- per-call-site analysis ----------

    private fun analyzeCallSite(
        ref: PsiReference,
        expected: List<RequirementAnnotation>,
        wrapperKind: WrapperKind,
        annotationFqns: Set<String>,
    ): CallSiteAnalysis? {
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

        // Walk to enclosing executable scope: PsiMethod, PsiLambdaExpression (Java),
        // or anything Kotlin-side that carries annotations (KtNamedFunction, KtLambdaExpression).
        val enclosingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        val enclosingExecutable: PsiElement? = enclosingMethod
            ?: nearestKotlinExecutable(element)

        val callerAnnotations = if (enclosingMethod != null) {
            // Method-level annotations, then containing-class as fallback (plan edge case 6).
            val mine = readAnnotations(enclosingMethod).filter { it.fqn in annotationFqns }
            if (mine.isNotEmpty()) mine
            else readAnnotations(enclosingMethod.containingClass ?: enclosingMethod)
                .filter { it.fqn in annotationFqns }
        } else {
            readAnnotationsAny(enclosingExecutable).filter { it.fqn in annotationFqns }
        }

        val wrapperHints = detectEnclosingWrappers(element, wrapperKind)

        val verdict = decide(expected, callerAnnotations, wrapperHints, wrapperKind, element)

        val callerSignature = renderCallerSignature(enclosingExecutable, element)

        return CallSiteAnalysis(
            fileUrl = vf.url,
            range = PsiStructureWalker.textRangeInfoOf(TextRange(absStart, absEnd), document),
            callerSignature = callerSignature,
            callerAnnotations = callerAnnotations,
            contextHints = verdict.contextHints,
            status = verdict.status,
            reason = verdict.reason,
        )
    }

    private fun nearestKotlinExecutable(element: PsiElement): PsiElement? {
        // Walk up until we hit something whose simple class name marks an executable scope.
        var node: PsiElement? = element.parent
        while (node != null && node !is PsiFile) {
            val simple = node.javaClass.simpleName
            if (simple == "KtNamedFunction" || simple == "KtLambdaExpression" ||
                simple == "KtPropertyAccessor" || simple == "KtConstructor" ||
                simple == "KtAnonymousInitializer" || simple == "KtClassInitializer"
            ) {
                return node
            }
            node = node.parent
        }
        return null
    }

    // ---------- wrapper recognition ----------

    /**
     * Walk parent chain looking for call expressions whose callee is a recognised wrapper.
     * Both Java (PsiMethodCallExpression) and Kotlin (KtCallExpression) shapes are checked
     * via simple-name matching to avoid a hard Kotlin PSI dep.
     */
    fun detectEnclosingWrappers(element: PsiElement, wrapperKind: WrapperKind): List<String> {
        val targets = when (wrapperKind) {
            WrapperKind.LOCK -> LOCK_WRAPPER_SIMPLE
            WrapperKind.THREADING -> EDT_WRAPPER_SIMPLE + BGT_WRAPPER_SIMPLE
        }
        val out = ArrayList<String>()
        var node: PsiElement? = element.parent
        var hops = 0
        while (node != null && node !is PsiFile && hops < 200) {
            val simple = node.javaClass.simpleName
            // Java: PsiMethodCallExpression — has .methodExpression.referenceName
            if (simple == "PsiMethodCallExpression") {
                val name = javaCallName(node)
                if (name != null && name in targets) out += "inside-$name"
            }
            // Kotlin: KtCallExpression — calleeExpression.text gives the simple name.
            if (simple == "KtCallExpression" || simple == "KtDotQualifiedExpression") {
                val name = kotlinCallName(node)
                if (name != null && name in targets) out += "inside-$name"
            }
            node = node.parent
            hops++
        }
        return out.distinct()
    }

    private fun javaCallName(call: Any): String? = runCatching {
        val mMethodExpression = call.javaClass.methods.firstOrNull { it.name == "getMethodExpression" && it.parameterCount == 0 }
            ?: return@runCatching null
        val methodExpression = mMethodExpression.invoke(call) ?: return@runCatching null
        val mReferenceName = methodExpression.javaClass.methods.firstOrNull { it.name == "getReferenceName" && it.parameterCount == 0 }
            ?: return@runCatching null
        mReferenceName.invoke(methodExpression) as? String
    }.getOrNull()

    private fun kotlinCallName(call: Any): String? = runCatching {
        val mCallee = call.javaClass.methods.firstOrNull { it.name == "getCalleeExpression" && it.parameterCount == 0 }
        val callee = mCallee?.invoke(call) ?: return@runCatching kotlinDotQualifiedSelectorName(call)
        // KtCallExpression.calleeExpression.text → the simple name string.
        val mText = callee.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
        (mText?.invoke(callee) as? String)?.substringAfterLast('.')?.substringBefore('(')
    }.getOrNull()

    private fun kotlinDotQualifiedSelectorName(dotExpr: Any): String? = runCatching {
        val mSelector = dotExpr.javaClass.methods.firstOrNull { it.name == "getSelectorExpression" && it.parameterCount == 0 }
        val selector = mSelector?.invoke(dotExpr) ?: return@runCatching null
        val mText = selector.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
        (mText?.invoke(selector) as? String)?.substringAfterLast('.')?.substringBefore('(')
    }.getOrNull()

    // ---------- decision tree ----------

    data class Verdict(val status: String, val reason: String, val contextHints: List<String>)

    private fun decide(
        expected: List<RequirementAnnotation>,
        callerAnnotations: List<RequirementAnnotation>,
        wrapperHints: List<String>,
        wrapperKind: WrapperKind,
        callSite: PsiElement,
    ): Verdict {
        if (expected.isEmpty()) {
            return Verdict("ok", "no contract", emptyList())
        }

        val callerKinds = callerAnnotations.map { it.kind }.toSet()
        val expectedKinds = expected.map { it.kind }.toSet()
        val hints = ArrayList<String>()
        hints.addAll(wrapperHints)
        callerAnnotations.forEach { hints += "@${it.fqn.substringAfterLast('.')}" }

        // --- Lock semantics ---
        if (wrapperKind == WrapperKind.LOCK) {
            // RequiresReadLockAbsence: caller must NOT be under read lock / write lock / inside RA/WA.
            if (RequirementKind.NO_READ_LOCK in expectedKinds) {
                val violates = RequirementKind.READ_LOCK in callerKinds ||
                    RequirementKind.WRITE_LOCK in callerKinds ||
                    wrapperHints.any { isReadOrWriteActionWrapper(it) }
                return if (violates) {
                    Verdict("mismatch", "@RequiresReadLockAbsence violated by enclosing lock/wrapper", hints.distinct())
                } else {
                    Verdict("ok", "caller is not under a read/write action", hints.distinct())
                }
            }
            // RequiresWriteLock: caller must have write lock annotation OR be inside WriteAction wrapper.
            if (RequirementKind.WRITE_LOCK in expectedKinds) {
                val ok = RequirementKind.WRITE_LOCK in callerKinds ||
                    wrapperHints.any { it.startsWith("inside-WriteAction") || it == "inside-runWriteAction" }
                if (ok) return Verdict("ok", "caller holds @RequiresWriteLock or WriteAction wrapper", hints.distinct())
                if (isInsideOpaqueDispatcher(callSite)) {
                    return Verdict("unknown", "caller is a lambda inside opaque dispatcher — cannot verify statically", hints.distinct())
                }
                return Verdict("mismatch", "no compatible write-lock annotation or WriteAction wrapper", hints.distinct())
            }
            // RequiresReadLock: caller can have either read OR write lock (write subsumes read), or be inside RA/WA.
            if (RequirementKind.READ_LOCK in expectedKinds) {
                val ok = RequirementKind.READ_LOCK in callerKinds ||
                    RequirementKind.WRITE_LOCK in callerKinds ||
                    wrapperHints.any { isReadOrWriteActionWrapper(it) }
                if (ok) return Verdict("ok", "caller holds a compatible lock annotation or wrapper", hints.distinct())
                if (isInsideOpaqueDispatcher(callSite)) {
                    return Verdict("unknown", "caller is a lambda inside opaque dispatcher — cannot verify statically", hints.distinct())
                }
                return Verdict("mismatch", "no compatible read-lock annotation or ReadAction wrapper", hints.distinct())
            }
        }

        // --- Threading semantics ---
        if (wrapperKind == WrapperKind.THREADING) {
            if (RequirementKind.EDT in expectedKinds) {
                val ok = RequirementKind.EDT in callerKinds ||
                    wrapperHints.any { isEdtWrapper(it) }
                if (ok) return Verdict("ok", "caller is on EDT (annotation or EDT wrapper)", hints.distinct())
                if (isInsideOpaqueDispatcher(callSite)) {
                    return Verdict("unknown", "caller is a lambda inside opaque dispatcher — cannot verify statically", hints.distinct())
                }
                return Verdict("mismatch", "no @RequiresEdt annotation or EDT-pushing wrapper", hints.distinct())
            }
            if (RequirementKind.BGT in expectedKinds) {
                val ok = RequirementKind.BGT in callerKinds ||
                    wrapperHints.any { isBgtWrapper(it) }
                if (ok) return Verdict("ok", "caller is on BGT (annotation or BGT-pushing wrapper)", hints.distinct())
                if (isInsideOpaqueDispatcher(callSite)) {
                    return Verdict("unknown", "caller is a lambda inside opaque dispatcher — cannot verify statically", hints.distinct())
                }
                return Verdict("mismatch", "no @RequiresBackgroundThread annotation or BGT-pushing wrapper", hints.distinct())
            }
            if (RequirementKind.BLOCKING_CONTEXT in expectedKinds) {
                val ok = RequirementKind.BLOCKING_CONTEXT in callerKinds
                return if (ok) Verdict("ok", "caller carries @RequiresBlockingContext", hints.distinct())
                else Verdict("mismatch", "caller missing @RequiresBlockingContext", hints.distinct())
            }
        }

        return Verdict("mismatch", "no compatible annotation", hints.distinct())
    }

    private fun isReadOrWriteActionWrapper(hint: String): Boolean =
        hint.startsWith("inside-ReadAction") ||
            hint.startsWith("inside-WriteAction") ||
            hint == "inside-runReadAction" ||
            hint == "inside-runWriteAction"

    private fun isEdtWrapper(hint: String): Boolean =
        hint == "inside-invokeLater" ||
            hint == "inside-invokeAndWait"

    private fun isBgtWrapper(hint: String): Boolean =
        hint == "inside-executeOnPooledThread" ||
            hint == "inside-runProcessWithProgressAsynchronously"

    /**
     * Heuristic: are we inside a lambda passed to a method whose name we don't recognise as a
     * known wrapper? Such lambdas could run on any thread → status "unknown" rather than
     * "mismatch". We check by walking up: if there's a PsiLambdaExpression or KtLambdaExpression
     * between us and the enclosing method/file, AND its parent call isn't already in our wrapper
     * sets, this is opaque.
     */
    private fun isInsideOpaqueDispatcher(element: PsiElement): Boolean {
        var node: PsiElement? = element.parent
        var hops = 0
        while (node != null && node !is PsiFile && hops < 200) {
            val simple = node.javaClass.simpleName
            if (simple == "PsiLambdaExpression" || simple == "KtLambdaExpression") {
                // Walk one or two more parents to find the surrounding call.
                var outer: PsiElement? = node.parent
                var more = 0
                while (outer != null && more < 4) {
                    val outerSimple = outer.javaClass.simpleName
                    if (outerSimple == "PsiMethodCallExpression") {
                        val name = javaCallName(outer)
                        if (name != null && name !in LOCK_WRAPPER_SIMPLE && name !in EDT_WRAPPER_SIMPLE && name !in BGT_WRAPPER_SIMPLE) {
                            return true
                        }
                        break
                    }
                    if (outerSimple == "KtCallExpression") {
                        val name = kotlinCallName(outer)
                        if (name != null && name !in LOCK_WRAPPER_SIMPLE && name !in EDT_WRAPPER_SIMPLE && name !in BGT_WRAPPER_SIMPLE) {
                            return true
                        }
                        break
                    }
                    outer = outer.parent
                    more++
                }
                return false
            }
            // Plain inner class with PsiMethod (anonymous Runnable) — also opaque.
            if (simple == "PsiAnonymousClass") return true
            node = node.parent
            hops++
        }
        return false
    }

    // ---------- scope ----------

    private fun resolveScope(project: Project, target: PsiElement, kind: String): SearchScope {
        return when (kind) {
            "file" -> GlobalSearchScope.fileScope(target.containingFile)
            "all" -> GlobalSearchScope.allScope(project)
            else -> GlobalSearchScope.projectScope(project)
        }
    }

    // ---------- rendering ----------

    /**
     * Signature of the caller's enclosing scope: e.g. `com.example.Foo.bar(Project)` for a
     * PsiMethod, or `com.example.Foo.bar.<lambda@42>` for a lambda inside one.
     */
    fun renderCallerSignature(enclosing: PsiElement?, callSite: PsiElement): String {
        if (enclosing is PsiMethod) {
            val cls = enclosing.containingClass?.qualifiedName ?: "<anon>"
            val params = enclosing.parameterList.parameters.joinToString(",") {
                it.type.presentableText
            }
            return "$cls.${enclosing.name}($params)"
        }
        if (enclosing == null) return "<file scope>"
        val simple = enclosing.javaClass.simpleName
        val line = try {
            val doc = enclosing.containingFile?.viewProvider?.document
                ?: FileDocumentManager.getInstance().getDocument(enclosing.containingFile?.virtualFile ?: return simple)
            if (doc != null) doc.getLineNumber(enclosing.textRange?.startOffset ?: 0) + 1 else null
        } catch (_: Throwable) {
            null
        }
        // Kotlin function name when available.
        val nameAccessor = enclosing.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
        val name = runCatching { nameAccessor?.invoke(enclosing) as? String }.getOrNull()
        val containing = nearestNamedAncestorName(enclosing) ?: "<top>"
        return when {
            simple == "KtNamedFunction" && name != null -> "$containing.$name@line$line"
            simple == "KtLambdaExpression" -> "$containing.<lambda@line$line>"
            simple == "PsiLambdaExpression" -> "$containing.<lambda@line$line>"
            else -> "$containing.$simple@line$line"
        }
    }

    private fun nearestNamedAncestorName(element: PsiElement): String? {
        var node: PsiElement? = element.parent
        while (node != null && node !is PsiFile) {
            if (node is PsiNamedElement) {
                val n = node.name
                if (!n.isNullOrBlank()) return n
            }
            node = node.parent
        }
        return null
    }

    private fun describeTarget(method: PsiMethod, document: Document?): TargetInfo {
        val vf = method.containingFile?.virtualFile
        val range = method.textRange ?: TextRange(0, 0)
        val cls = method.containingClass?.qualifiedName ?: "<anon>"
        val params = method.parameterList.parameters.joinToString(",") { it.type.presentableText }
        return TargetInfo(
            psiClass = method.javaClass.simpleName,
            declarationName = "${cls}.${method.name}($params)",
            fileUrl = vf?.url ?: "",
            range = PsiStructureWalker.textRangeInfoOf(range, document),
            text = method.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(120),
        )
    }
}
