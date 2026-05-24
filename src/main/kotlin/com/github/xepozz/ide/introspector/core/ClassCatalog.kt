package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.ClassEntry
import com.github.xepozz.ide.introspector.model.DeclarationRange
import com.github.xepozz.ide.introspector.model.ListClassesResponse
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPackage
import com.intellij.psi.search.GlobalSearchScope

/**
 * Headless engine for `code.list_classes_in_module` + `code.list_classes_in_package`.
 *
 * Strict, scope-shaped enumeration — NOT fuzzy. Top-level `PsiClass`es only; anonymous,
 * inner, and local classes are excluded (v1 limitation — see plan).
 *
 * Both entry points share the same response shape ([ListClassesResponse]) and the same
 * Kotlin-light-class detection. The caller MUST already be inside a read action — see
 * [com.github.xepozz.ide.introspector.util.readActionBlocking]. No EDT bouncing.
 *
 * **Time + size budgets** (CLAUDE.md hard rule: 10 s cap):
 *  - [WALL_CLOCK_MS] (10s) wall-clock deadline checked between files/packages.
 *  - [limit] clamped to `[1, MAX_LIMIT]`; on hit `truncated=true`.
 *  - Per-file `runCatching { … }` — one corrupt file doesn't tank the call.
 */
object ClassCatalog {

    const val MAX_LIMIT: Int = 5_000
    const val WALL_CLOCK_MS: Long = 10_000L

    private val ALL_KINDS: Set<String> = setOf(
        "class", "interface", "enum", "record", "annotation", "kotlinFileFacade",
    )

    /** Optional `KtLightClassForFacade` FQN — null when Kotlin plugin not present. */
    private val ktFacadeClass: Class<*>? = runCatching {
        Class.forName("org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade")
    }.getOrNull()

    /**
     * Module variant — enumerates top-level classes across the module's source roots.
     * Filters by `packagePrefix`, `kinds`, `includeTests`, `includeGenerated`.
     *
     * Throws nothing on a missing module — returns an empty result with `total=0` so the
     * caller decides whether to surface an MCP error.
     */
    fun listInModule(
        project: Project,
        moduleName: String,
        packagePrefix: String?,
        includeTests: Boolean,
        includeGenerated: Boolean,
        kinds: Collection<String>,
        limit: Int,
        clock: Clock = Clock.SYSTEM,
    ): Pair<ListClassesResponse, ModuleLookup> {
        val clamped = clampLimit(limit)
        val allowedKinds = kinds.toSet().intersect(ALL_KINDS)
        val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
            ?: return ListClassesResponse(
                scope = moduleName,
                classes = emptyList(),
                total = 0,
                truncated = false,
                timedOut = false,
                note = null,
            ) to ModuleLookup.NOT_FOUND

        val deadline = clock.now() + WALL_CLOCK_MS
        val collected = mutableListOf<ClassEntry>()
        var total = 0
        var timedOut = false

        val fileIndex = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        val iterator = ContentIterator { vf ->
            if (clock.now() >= deadline) {
                timedOut = true
                return@ContentIterator false
            }
            if (vf.isDirectory) return@ContentIterator true
            if (!isLikelyClassSourceFile(vf)) return@ContentIterator true
            if (!includeTests && fileIndex.isInTestSourceContent(vf)) return@ContentIterator true
            if (!includeGenerated && fileIndex.isInGeneratedSources(vf)) return@ContentIterator true

            runCatching {
                val psiFile = psiManager.findFile(vf) as? PsiClassOwner ?: return@runCatching
                val pkgName = psiFile.packageName ?: ""
                if (!matchesPackagePrefix(pkgName, packagePrefix)) return@runCatching
                for (cls in psiFile.classes) {
                    if (clock.now() >= deadline) {
                        timedOut = true
                        return@runCatching
                    }
                    val entry = toEntry(cls, allowedKinds) ?: continue
                    total++
                    if (collected.size < clamped) {
                        collected.add(entry)
                    }
                }
            }
            true
        }
        ModuleRootManager.getInstance(module).fileIndex.iterateContent(iterator)

        val truncated = total > collected.size
        return ListClassesResponse(
            scope = moduleName,
            classes = collected,
            total = total,
            truncated = truncated,
            timedOut = timedOut,
            note = noteIfDumb(project),
        ) to ModuleLookup.FOUND
    }

    /**
     * Package variant — enumerates top-level classes via [JavaPsiFacade.findPackage] /
     * [PsiPackage.getClasses]. Recurses into `subPackages` when [recursive] = true.
     *
     * `includeLibraries=false` (default) restricts to project sources via [projectScope];
     * `true` widens to [GlobalSearchScope.allScope] — JDK + every attached library jar.
     */
    fun listInPackage(
        project: Project,
        packageFqn: String,
        recursive: Boolean,
        includeLibraries: Boolean,
        kinds: Collection<String>,
        limit: Int,
        clock: Clock = Clock.SYSTEM,
    ): ListClassesResponse {
        val clamped = clampLimit(limit)
        val allowedKinds = kinds.toSet().intersect(ALL_KINDS)
        val facade = JavaPsiFacade.getInstance(project)
        val rootPkg = facade.findPackage(packageFqn) ?: return ListClassesResponse(
            scope = packageFqn,
            classes = emptyList(),
            total = 0,
            truncated = false,
            timedOut = false,
            note = noteIfDumb(project),
        )

        val scope: GlobalSearchScope = if (includeLibraries)
            GlobalSearchScope.allScope(project)
        else
            GlobalSearchScope.projectScope(project)

        val deadline = clock.now() + WALL_CLOCK_MS
        val collected = mutableListOf<ClassEntry>()
        var total = 0
        var timedOut = false

        // BFS to keep results stable + bounded — depth-first recursion would risk
        // unbalanced traversal on large package trees.
        val queue: ArrayDeque<PsiPackage> = ArrayDeque()
        queue.add(rootPkg)
        val seen = HashSet<String>()
        seen.add(rootPkg.qualifiedName)

        outer@ while (queue.isNotEmpty()) {
            if (clock.now() >= deadline) {
                timedOut = true
                break
            }
            val pkg = queue.removeFirst()
            runCatching {
                for (cls in pkg.getClasses(scope)) {
                    if (clock.now() >= deadline) {
                        timedOut = true
                        return@runCatching
                    }
                    val entry = toEntry(cls, allowedKinds) ?: continue
                    total++
                    if (collected.size < clamped) {
                        collected.add(entry)
                    }
                }
            }
            if (timedOut) break@outer
            if (recursive) {
                runCatching {
                    for (sub in pkg.subPackages) {
                        val name = sub.qualifiedName
                        if (seen.add(name)) queue.addLast(sub)
                    }
                }
            }
        }

        val truncated = total > collected.size
        return ListClassesResponse(
            scope = packageFqn,
            classes = collected,
            total = total,
            truncated = truncated,
            timedOut = timedOut,
            note = noteIfDumb(project),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Filter accepts an FQN under `prefix` recursively (`com.acme` matches
     *  `com.acme.X` and `com.acme.sub.X` but NOT `com.acmex.Y`). */
    fun matchesPackagePrefix(packageFqn: String, prefix: String?): Boolean {
        if (prefix.isNullOrEmpty()) return true
        if (packageFqn == prefix) return true
        return packageFqn.startsWith("$prefix.")
    }

    /** Clamp/validate the user-supplied limit. */
    fun clampLimit(limit: Int): Int {
        require(limit in 1..MAX_LIMIT) {
            "limit must be in 1..$MAX_LIMIT, got $limit"
        }
        return limit
    }

    /** Filter on file extension before touching PSI — avoids paying parse cost on docs/binaries. */
    private fun isLikelyClassSourceFile(vf: VirtualFile): Boolean {
        val ext = vf.extension ?: return false
        return ext == "java" || ext == "kt"
    }

    /** Build a [ClassEntry] from a [PsiClass]; returns null when the class is anonymous,
     *  has no FQN, or its kind isn't in [allowedKinds]. */
    private fun toEntry(cls: PsiClass, allowedKinds: Set<String>): ClassEntry? {
        val fqn = cls.qualifiedName ?: return null
        val simple = cls.name ?: return null
        val kind = classifyKind(cls)
        if (kind !in allowedKinds) return null
        val pkg = packageOf(fqn, simple)
        val containing = cls.containingFile
        val fileUrl = containing?.virtualFile?.url
        val range = cls.textRange?.let { DeclarationRange(it.startOffset, it.endOffset) }
        val byteLength = containing?.textLength
        return ClassEntry(
            fqn = fqn,
            simpleName = simple,
            pkg = pkg,
            kind = kind,
            fileUrl = fileUrl,
            declarationRange = range,
            byteLength = byteLength,
        )
    }

    /** Map a [PsiClass] to one of our 6 kinds. Kotlin file facades have a dedicated kind
     *  so agents can distinguish them from hand-written `XxxKt` classes. */
    private fun classifyKind(cls: PsiClass): String {
        val facade = ktFacadeClass
        if (facade != null && facade.isInstance(cls)) return "kotlinFileFacade"
        return when {
            cls.isAnnotationType -> "annotation"
            cls.isEnum -> "enum"
            cls.isInterface -> "interface"
            cls.isRecord -> "record"
            else -> "class"
        }
    }

    /** Derive package FQN from class FQN + simple name (avoids one PsiPackage lookup per class). */
    private fun packageOf(fqn: String, simpleName: String): String {
        if (!fqn.endsWith(".$simpleName")) {
            // Inner/local class smell — drop everything after the last dot defensively. The
            // module/package iteration paths only yield top-level classes today, but keeping the
            // fallback prevents a future regression from producing `pkg="com.acme.Outer"` rows.
            val idx = fqn.lastIndexOf('.')
            return if (idx < 0) "" else fqn.substring(0, idx)
        }
        val idx = fqn.length - simpleName.length - 1
        return if (idx <= 0) "" else fqn.substring(0, idx)
    }

    private fun noteIfDumb(project: Project): String? =
        if (DumbService.isDumb(project)) "Project is indexing; results may be incomplete" else null

    /** Marker for module lookups so the toolset can convert `NOT_FOUND` to `McpExpectedError`
     *  without conflating it with a real empty-module result. */
    enum class ModuleLookup { FOUND, NOT_FOUND }

    /** Tiny seam so tests can inject a fake clock and exercise the wall-clock deadline
     *  without `Thread.sleep`. Production callers omit it. */
    fun interface Clock {
        fun now(): Long
        companion object {
            val SYSTEM: Clock = Clock { System.currentTimeMillis() }
        }
    }

    // For platform tests that need to drive the module iteration outside the toolset.
    @Suppress("unused")
    internal fun listInModuleForTest(
        project: Project,
        module: Module,
        moduleName: String = module.name,
        packagePrefix: String? = null,
        includeTests: Boolean = false,
        includeGenerated: Boolean = true,
        kinds: Collection<String> = ALL_KINDS,
        limit: Int = 1000,
        clock: Clock = Clock.SYSTEM,
    ): ListClassesResponse {
        return listInModule(
            project = project,
            moduleName = moduleName,
            packagePrefix = packagePrefix,
            includeTests = includeTests,
            includeGenerated = includeGenerated,
            kinds = kinds,
            limit = limit,
            clock = clock,
        ).first
    }
}
