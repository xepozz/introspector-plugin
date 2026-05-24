# `code.list_classes_in_module` + `code.list_classes_in_package`

## Purpose & motivation

Two catalog tools extending `code.*` from "resolve one FQN" to "enumerate many FQNs
by scope". Today an agent wanting "every class in `com.acme.billing`" or "every class
in module `web-frontend`" has to guess through `code.find_class` one at a time, or
use JetBrains' fuzzy `search_symbol`. These are strict-by-scope and share a
`ClassEntry` shape ready to feed into `code.find_class` / `code.get_source` /
`code.list_members`.

**Success criterion**: list every project class under `com.acme.billing.*` (recursive)
or every class in module `payments-core` in one MCP call, returning FQN + fileUrl
ready for a `code.get_source` follow-up.

## Tool specification

### `code.list_classes_in_module`

```kotlin
@McpTool(name = "code.list_classes_in_module")
@McpDescription("""…see below…""")
suspend fun code_list_classes_in_module(
    @McpDescription("Module name as reported by IntelliJ (matches JetBrains MCP get_project_modules).")
    moduleName: String,
    @McpDescription("Optional package-FQN prefix filter. Null/empty matches all.")
    packagePrefix: String? = null,
    @McpDescription("Include test source roots. Default false.")
    includeTests: Boolean = false,
    @McpDescription("Include generated source roots (build/, .gen/, kapt). Default true.")
    includeGenerated: Boolean = true,
    @McpDescription("Restrict to kinds. Allowed: 'class','interface','enum','record','annotation','kotlinFileFacade'.")
    kinds: List<String> = listOf("class","interface","enum","record","annotation","kotlinFileFacade"),
    @McpDescription("Cap on results. Default 1000, hard upper bound 5000.")
    limit: Int = 1000,
): ListClassesResponse
```

**`@McpDescription` (verbatim, trim-margin):**

```
|Enumerates every top-level class declared in one project module's source roots.
|Strict, structural listing — NOT fuzzy. Companion of JetBrains' search_symbol;
|use this when you know the scope but not the names.
|
|Use this when: an agent asks "list all classes in module web-frontend", "what
|classes live under com.acme.billing in module payments-core?", "every annotation
|in module shared". Returns ClassEntry rows ready for code.get_source / code.list_members.
|
|Do NOT use this when:
|  - You want classes by package across all modules — use code.list_classes_in_package.
|  - You want fuzzy / partial-name search — use JetBrains' search_symbol.
|  - You want source text — call code.get_source on each fqn.
|  - The IDE isn't IntelliJ IDEA / Android Studio (code.* doesn't load elsewhere).
|
|Returns: { scope, classes: ClassEntry[], total, truncated, timedOut } where each
|ClassEntry has fqn, simpleName, package, kind ('class'|'interface'|'enum'|'record'
||'annotation'|'kotlinFileFacade'), fileUrl, declarationRange, byteLength.
|Anonymous + inner classes are excluded. Kotlin file-level functions surface as
|kind='kotlinFileFacade' (the synthetic FooKt class).
|
|Examples:
|  moduleName="payments-core"                                   — all production classes
|  moduleName="payments-core", includeTests=true                 — incl. tests
|  moduleName="web-frontend", packagePrefix="com.acme.billing"   — sub-tree
|  moduleName="shared", kinds=["annotation"]                     — annotation surface
```

### `code.list_classes_in_package`

```kotlin
@McpTool(name = "code.list_classes_in_package")
@McpDescription("""…see below…""")
suspend fun code_list_classes_in_package(
    @McpDescription("Fully-qualified package name. Empty string '' = default/root package.")
    packageFqn: String,
    @McpDescription("Recurse into sub-packages. Default false.")
    recursive: Boolean = false,
    @McpDescription("Include library jars (rt.jar alone has ~30k classes). Default false — project sources only.")
    includeLibraries: Boolean = false,
    @McpDescription("Restrict to kinds. Allowed: 'class','interface','enum','record','annotation','kotlinFileFacade'.")
    kinds: List<String> = listOf("class","interface","enum","record","annotation","kotlinFileFacade"),
    @McpDescription("Cap on results. Default 500, hard upper bound 5000.")
    limit: Int = 500,
): ListClassesResponse
```

**`@McpDescription` (verbatim, trim-margin):**

```
|Lists all top-level classes in one package FQN, across every module of the project
|(and optionally library jars). Strict-by-package; the cross-module counterpart of
|code.list_classes_in_module.
|
|Use this when: an agent asks "what's in com.acme.billing?", "list everything under
|java.util (recursive)?", "annotations in javax.persistence?". Cheap probe before
|code.get_source / code.list_members.
|
|Do NOT use this when:
|  - You want a module scope — use code.list_classes_in_module.
|  - You want fuzzy / partial-name search — use JetBrains' search_symbol.
|  - The package doesn't exist (returns empty, not an error — check `total`).
|
|Returns: { scope, classes: ClassEntry[], total, truncated, timedOut }. Same
|ClassEntry shape as code.list_classes_in_module. Anonymous + inner classes excluded.
|
|Library scope: includeLibraries=false (default) means project sources only.
|Enabling it widens to GlobalSearchScope.allScope and can return tens of thousands
|of classes (rt.jar alone ~30k). Always combine includeLibraries=true with a narrow
|package and tight limit.
|
|Examples:
|  packageFqn="com.acme.billing"                                  — direct children
|  packageFqn="com.acme.billing", recursive=true                  — whole subtree
|  packageFqn="java.util", includeLibraries=true                  — JDK util top level
|  packageFqn=""                                                  — default/root package
```

## Args + response model

New file `model/args/CodeArgs.kt` (per-group split matches `PsiArgs`, `UiArgs`,
`ExecArgs`, `ScreenshotArgs`):

```kotlin
@Serializable
data class ListClassesInModuleArgs(
    val moduleName: String,
    val packagePrefix: String? = null,
    val includeTests: Boolean = false,
    val includeGenerated: Boolean = true,
    val kinds: List<String> = listOf("class","interface","enum","record","annotation","kotlinFileFacade"),
    val limit: Int = 1000,
)

@Serializable
data class ListClassesInPackageArgs(
    val packageFqn: String,
    val recursive: Boolean = false,
    val includeLibraries: Boolean = false,
    val kinds: List<String> = listOf("class","interface","enum","record","annotation","kotlinFileFacade"),
    val limit: Int = 500,
)
```

Append to `model/ClassSourceInfo.kt`:

```kotlin
@Serializable data class DeclarationRange(val startOffset: Int, val endOffset: Int)

@Serializable
data class ClassEntry(
    val fqn: String,
    val simpleName: String,
    /** Package FQN; "" for default. */
    val `package`: String,
    /** "class"|"interface"|"enum"|"record"|"annotation"|"kotlinFileFacade". */
    val kind: String,
    val fileUrl: String?,
    val declarationRange: DeclarationRange? = null,
    /** Optional file size hint so agents can budget code.get_source follow-ups. */
    val byteLength: Int? = null,
)

@Serializable
data class ListClassesResponse(
    /** Echoes the module name (module variant) or packageFqn (package variant). */
    val scope: String,
    val classes: List<ClassEntry>,
    val total: Int,
    val truncated: Boolean,
    /** True when the 10s cap fired before enumeration finished. */
    val timedOut: Boolean = false,
    /** Set when project is in dumb mode and results may be incomplete. */
    val note: String? = null,
)
```

## IntelliJ APIs used

- `JavaPsiFacade.getInstance(project).findPackage(fqn)` → `PsiPackage`.
- `PsiPackage.getClasses(GlobalSearchScope)` — top-level classes in this package.
- `PsiPackage.subPackages` — for recursive walk.
- `ModuleManager.getInstance(project).findModuleByName(name)`.
- `GlobalSearchScope.moduleScope(module)` / `projectScope(project)` / `allScope(project)`.
- `ModuleRootManager.getInstance(module).fileIndex.iterateContent { vf -> … }` —
  module-wide VFS iteration when filtering test/generated.
- `ProjectFileIndex.isInTestSourceContent(vf)` / `isInGeneratedSourceContent(vf)`.
- `PsiManager.findFile(vf)` cast to `PsiClassOwner` → `.classes` returns every
  top-level `PsiClass` (Kotlin's `KtFile` implements `PsiClassOwner` via light
  classes, so the same code handles `.java` and `.kt` uniformly).
- Kotlin file facade detection: `org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade`
  — accessed defensively (reflective `Class.forName` guard) so the tool still works
  in pure-Java projects. When matched → `kind="kotlinFileFacade"`.
- `DumbService.isDumb(project)` — surface a hint note when indexing is in progress.

All stable platform API. `KtLightClassForFacade` is Kotlin-plugin-only and accessed
behind a guard.

## Threading & EDT model

Pure PSI read — wrap in `readActionBlocking { … }` (same helper as other `code.*`
methods). **No EDT bouncing**, no Swing, no model mutation. No cache: source roots
change on every edit, invalidation isn't worth it.

## Timeout strategy

Hard 10 s cap per CLAUDE.md. Mitigations:

1. `includeLibraries=false` default on package variant — without this, `java.util`
   recursive enumerates ~30k JDK classes.
2. `limit` clamped to `[1, 5000]` via `require(limit in 1..5000)`. When hit set
   `truncated=true`.
3. Wall-clock deadline (`System.nanoTime() + 10_000_000_000L`, reusing
   `EdtHelpers.DEFAULT_EDT_TIMEOUT_MS`) checked between files/packages; bail with
   `timedOut=true` + partial results.
4. VFS `iterateContent` for the module variant is O(files) and avoids materializing
   PSI for files we'll filter out.
5. Per-file/per-package `runCatching { … }.getOrElse { … }` so one corrupt entry
   doesn't tank the call (mirrors `ExtensionPointInspector.collectFromArea`).

If a realistic worst case still exceeds 10 s, **narrow the tool** (require
`packagePrefix`, lower limits) rather than raising the cap.

## Edge cases

1. **Module not found** — throw `McpExpectedError("Module not found: <name>. Use
   get_project_modules to list available modules.", …)`.
2. **Empty / missing package** — return `ListClassesResponse(scope, [], 0, false)`.
   Not an error. Matches `arch.list_services` convention.
3. **Mixed Java + Kotlin module** — `PsiClassOwner.classes` returns Java classes
   plus light-class wrappers for Kotlin; no per-language branching needed.
4. **Kotlin file facades** — top-level functions/properties synthesize a `FooKt`
   class via `KtLightClassForFacade`. Map to `kind="kotlinFileFacade"` so agents
   can distinguish from a hand-written `class FooKt`. Skip entirely when
   `"kotlinFileFacade"` is not in `kinds`.
5. **Default/root package** — `packageFqn=""` is valid and documented.
6. **Anonymous + inner classes** — excluded in v1 (top-level only via
   `PsiClassOwner.classes`). Inner-class support deferred (see Open Qs).
7. **Generated sources** — controlled by `includeGenerated` on the module variant.
   The package variant has no such knob (its scope is package-shaped, not directory-
   shaped); documented as an explicit asymmetry.
8. **PsiClass without qualifiedName** — local/synthetic classes; skip silently.
9. **Dumb mode** — `JavaPsiFacade.findPackage` returns possibly-stale results;
   when `DumbService.isDumb(project)` set `note="Project is indexing; results may
   be incomplete"`.
10. **Module with no source roots** — empty `classes`, `total=0`. Not an error.
11. **Library scope** — `includeLibraries=true` uses
    `GlobalSearchScope.allScope(project)`; `false` uses `projectScope`. Never
    `everythingScope` (covers non-Java VFS too).

## Files to create/modify

NO new tool group, NO new META-INF wiring. `code.*` already loads behind
`java-introspect.xml` (depends on `com.intellij.modules.java`) — the exact gate
these tools need.

| Path | Op | What |
|------|----|------|
| `tools/CodeSourceToolset.kt` | Edit | Add two `@McpTool` methods (~40 LOC + descriptions). |
| `core/ClassCatalog.kt` | Create | Headless enumeration: `listInModule(project, args)` + `listInPackage(project, args)`. Pure-PSI reads. Kept separate from `ClassSourceResolver` to preserve its "resolve-one" focus. |
| `model/ClassSourceInfo.kt` | Edit | Append `ClassEntry`, `DeclarationRange`, `ListClassesResponse`. |
| `model/args/CodeArgs.kt` | Create | `ListClassesInModuleArgs`, `ListClassesInPackageArgs`. |
| `src/test/kotlin/.../core/ClassCatalogFilterTest.kt` | Create | Unit: kind filter, packagePrefix matching, limit truncation. |
| `src/test/kotlin/.../core/platform/ClassCatalogPlatformTest.kt` | Create | `BasePlatformTestCase` fixture: two modules with Java + Kotlin sources + a generated dir. |

`docs/MCP_TOOLS.md` regenerates from annotations on `./gradlew compileKotlin`.

## Test plan

**Unit (`ClassCatalogFilterTest.kt`)** — pure JVM:
- kind filter accepts allowed names, silently rejects unknowns.
- `packagePrefix=null` matches everything; `"com.acme"` matches `com.acme.*` but not `com.acmex`.
- `limit` invalid → `IllegalArgumentException`; oversize result → `truncated=true`.
- `kotlinFileFacade` excluded when not in `kinds`.

**Platform (`ClassCatalogPlatformTest.kt`)** — `BasePlatformTestCase` with multi-module fixture:
- `testListInModuleTopLevelOnly` — no inner / anonymous.
- `testListInModuleIncludeTests` — toggle returns test-root classes.
- `testListInModuleExcludesGenerated` — `includeGenerated=false` hides them.
- `testListInModulePackagePrefix` — only `com.acme.billing.*` returned.
- `testListInModuleAnnotationKind` — `kinds=["annotation"]` filter.
- `testListInPackageNonRecursive` vs `testListInPackageRecursive`.
- `testListInPackageExcludesLibrariesByDefault` — `java.util` returns empty without `includeLibraries`.
- `testListInPackageDefaultPackage` — `packageFqn=""` works.
- `testKotlinFileFacadeKind` — top-level Kotlin `fun foo()` produces `kind="kotlinFileFacade"`.
- `testModuleNotFoundThrowsMcpExpectedError`.
- `testLimitTruncates` — synthetic fixture > limit → `truncated=true`.
- `testTimedOutFlag` — inject a tiny deadline via test seam; verify `timedOut=true`
  with partial results.

Toolset wrappers are thin enough not to need their own test class.

## Estimated effort

| Step | Hours |
|------|-------|
| Models + `CodeArgs.kt` | 0.5 |
| `ClassCatalog.kt` (both methods, deadline, facade detection) | 3 |
| Two `@McpTool` wrappers + descriptions | 1 |
| Unit tests | 1 |
| Platform tests (multi-module fixture is the slow bit) | 2 |
| Doc-gen + manual `runIde` smoke | 0.5 |
| **Total** | **~1 day** |

## Open questions / risks

1. **Inner classes** — v1 excludes (top-level only). Adding
   `includeInnerClasses: Boolean = false` via `PsiClass.innerClasses` is cheap but
   inflates responses and complicates `fqn` (uses `$`). Defer to v2 under demand.
2. **Per-class size** (`byteLength`) — recommend **yes in v1**; we already touch the
   file, the cost is one `PsiFile.textLength` read, and it lets agents prioritize
   big classes when budgeting `code.get_source` follow-ups. Line count is
   redundant given byte length.
3. **Kotlin object / companion-object** — currently surface as `kind="class"` via
   light-class wrapping. Separate `kotlinObject` / `kotlinCompanion` kinds would
   need Kotlin-plugin-only reflection. **Defer**, note in description.
4. **`fileUrl` per class** — recommend **yes in v1**, included. Makes
   `code.get_source` follow-ups one round-trip cheaper and lets agents jump
   straight to a file without an intermediate `code.find_class`.
5. **Deadline injection** for `timedOut` testing without `Thread.sleep` flakiness
   needs a `Clock`/`Deadline` seam — small refactor, budgeted above.
6. **Library scope edge cost** — `includeLibraries=true` without a tight package
   can hit the 10 s cap. `truncated=true` + `timedOut=true` is a correct outcome,
   but the description warns loudly so the agent doesn't naively retry.
7. **PyCharm CE / GoLand** — `java-introspect.xml` already gates on
   `com.intellij.modules.java`; no extra wiring needed.

## References

- Existing code: `tools/CodeSourceToolset.kt#code_find_class` (read-action +
  `requireProject` + `McpExpectedError` shape), `core/ClassSourceResolver.kt`
  (sibling `object` headless helper — new `ClassCatalog.kt` mirrors layout),
  `core/PluginInventory.kt` (defensive per-entry `runCatching` pattern).
- IntelliJ source:
  - `JavaPsiFacade`: https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/JavaPsiFacade.java
  - `PsiPackage`: https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/PsiPackage.java
  - `GlobalSearchScope`: https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/search/GlobalSearchScope.java
  - `ProjectFileIndex`: https://github.com/JetBrains/intellij-community/blob/master/platform/projectModel-api/src/com/intellij/openapi/roots/ProjectFileIndex.kt
  - `KtLightClassForFacade` (Kotlin plugin, optional): https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/base/light-classes/src/org/jetbrains/kotlin/asJava/classes/KtLightClassForFacade.kt
- JetBrains MCP equivalent: `search_symbol` (fuzzy class/method/field lookup —
  different shape: ranks fuzzy matches, doesn't enumerate a scope). These tools
  are the strict-by-scope complement.
