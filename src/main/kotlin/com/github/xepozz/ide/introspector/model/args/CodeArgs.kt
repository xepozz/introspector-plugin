package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

/**
 * Args for `code.list_classes_in_module` — strict module-scoped enumeration of top-level
 * classes via `ModuleRootManager`/`ProjectFileIndex` + `PsiClassOwner.classes`.
 *
 * `kinds` allows `'class' | 'interface' | 'enum' | 'record' | 'annotation' | 'kotlinFileFacade'`
 * — Kotlin file-level functions/properties surface synthetically as `kotlinFileFacade` so
 * agents can distinguish from a hand-written `FooKt class`. Unknown entries silently
 * filter out (never match).
 *
 * Defaults mirror the plan in `docs/plans/code-class-catalog.md`:
 *  - `includeTests=false` — production sources only.
 *  - `includeGenerated=true` — kapt/ksp/protobuf outputs are usually wanted.
 *  - `limit=1000` — clamped to `[1, 5000]` at the toolset boundary.
 */
@Serializable
data class ListClassesInModuleArgs(
    val moduleName: String,
    val packagePrefix: String? = null,
    val includeTests: Boolean = false,
    val includeGenerated: Boolean = true,
    val kinds: List<String> = listOf("class", "interface", "enum", "record", "annotation", "kotlinFileFacade"),
    val limit: Int = 1000,
)

/**
 * Args for `code.list_classes_in_package` — cross-module package-scoped enumeration via
 * `JavaPsiFacade.findPackage(fqn).getClasses(scope)` plus optional recursion into
 * `PsiPackage.subPackages`.
 *
 * `includeLibraries=false` is the safety knob — rt.jar alone has ~30k classes; always
 * pair `includeLibraries=true` with a narrow package and a tight `limit`. Same `kinds`
 * vocabulary as the module variant.
 */
@Serializable
data class ListClassesInPackageArgs(
    val packageFqn: String,
    val recursive: Boolean = false,
    val includeLibraries: Boolean = false,
    val kinds: List<String> = listOf("class", "interface", "enum", "record", "annotation", "kotlinFileFacade"),
    val limit: Int = 500,
)
