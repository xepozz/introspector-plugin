# `code.list_classes_in_module` + `code.list_classes_in_package`

## Purpose & motivation

Two complementary catalog tools that extend the existing `code.*` group from "resolve
one FQN" to "enumerate many FQNs by scope". They give an agent strict, structural
listings of all top-level classes in a module or package — cheaper and more predictable
than JetBrains' fuzzy `search_symbol`. Today an agent who wants "every class in
`com.acme.billing`" or "every class in module `web-frontend`" has to either guess FQNs
one at a time through `code.find_class`, or use JetBrains' `search_symbol` which is
fuzzy-by-query and ranks results rather than enumerating them.

Both tools share the same `ClassEntry` shape so an agent can pipe results into
`code.find_class` / `code.get_source` / `code.list_members` follow-ups.

**Success criterion**: an agent can list every project class under
`com.acme.billing.*` (recursive) or every class in module `payments-core` in one MCP
call and get back FQN + file URL ready for a `code.get_source` follow-up.

## Tool specification

### `code.list_classes_in_module`

**Signature:**

```kotlin
@McpTool(name = "code.list_classes_in_module")
@McpDescription("""…see below…""")
suspend fun code_list_classes_in_module(
    @McpDescription("Module name as reported by IntelliJ (matches JetBrains MCP's get_project_modules output).")
    moduleName: String,
    @McpDescription("Optional package-FQN prefix filter. Empty / null matches everything. Use to scope a module-wide listing to a sub-tree.")
    packagePrefix: String? = null,
    @McpDescription("Include test source roots in addition to production. Default false.")
    includeTests: Boolean = false,
    @McpDescription("Include generated source roots (build/, .gen/, kapt, build-generated). Default true — set false to focus on hand-written code.")
    includeGenerated: Boolean = true,
    @McpDescription("Restrict to these class kinds. Default all. Allowed: 'class', 'interface', 'enum', 'record', 'annotation', 'kotlinFileFacade'.")
    kinds: List<String> = listOf("class", "interface", "enum", "record", "annotation", "kotlinFileFacade"),
    @McpDescription("Cap on returned classes. Default 1000. Hard upper bound 5000.")
    limit: Int = 1000,
): ListClassesResponse
```

**`@McpDescription` draft** (copy-paste verbatim, trim-margin format):

```
|Enumerates every top-level class declared in the source roots of one project module.
|Strict, structural listing — NOT fuzzy. The companion of JetBrains' search_symbol
|(which is fuzzy-by-query); use this tool when you know the scope but not the names.
|
|Use this when: an agent asks "list all classes in module web-frontend", "what classes
|live under com.acme.billing in module payments-core?", "give me every annotation
|defined in module shared". Returns ClassEntry rows ready to feed into code.get_source
|or code.list_members.
|
|Do NOT use this when:
|  - You want classes by package across the whole project — use code.list_classes_in_package.
|  - You're searching by name with typos / partial matches — use JetBrains' search_symbol
|    (fuzzy ranking).
|  - You want the class's source text — call code.get_source on each FQN.
|  - The IDE isn't IntelliJ IDEA / Android Studio (PyCharm CE etc. don't load code.*).
|
|Returns: { module, classes: ClassEntry[], total, truncated, timedOut } where each
|ClassEntry has fqn, simpleName, package, kind ('class'|'interface'|'enum'|'record'|
|'annotation'|'kotlinFileFacade'), fileUrl, declarationRange ({startOffset, endOffset}).
|Anonymous and inner classes are excluded — top-level declarations only. Kotlin file-
|level functions surface as kind='kotlinFileFacade' (the synthetic FooKt class).
|
|Scope cost: a module with ~10 000 files takes well under 1 s; we cap at 10 s and set
|truncated/timedOut accordingly. Narrow with packagePrefix when possible.
|
|Examples:
|  moduleName="payments-core"                                            — all production classes
|  moduleName="payments-core", includeTests=true                          — incl. test sources
|  moduleName="web-frontend", packagePrefix="com.acme.billing"            — sub-tree
|  moduleName="shared", kinds=["annotation"]                              — annotation surface
```

### `code.list_classes_in_package`

**Signature:**

```kotlin
@McpTool(name = "code.list_classes_in_package")
@McpDescription("""…see below…""")
suspend fun code_list_classes_in_package(
    @McpDescription("Fully-qualified package name. Empty string '' = default/root package.")
    packageFqn: String,
    @McpDescription("Recurse into sub-packages. Default false (this package only).")
    recursive: Boolean = false,
    @McpDescription("Include library jar contents (rt.jar alone exposes ~30 000 classes). Default false — project sources only.")
    includeLibraries: Boolean = false,
    @McpDescription("Restrict to these class kinds. Default all. Allowed: 'class', 'interface', 'enum', 'record', 'annotation', 'kotlinFileFacade'.")
    kinds: List<String> = listOf("class", "interface", "enum", "record", "annotation", "kotlinFileFacade"),
    @McpDescription("Cap on returned classes. Default 500. Hard upper bound 5000.")
    limit: Int = 500,
): ListClassesResponse
```

**`@McpDescription` draft** (copy-paste verbatim):

```
|Lists all top-level classes in one package FQN, across every module of the project
|(and optionally library jars). Strict-by-package; the cross-module counterpart of
|code.list_classes_in_module when the agent is exploring a specific package.
|
|Use this when: an agent asks "what's in com.acme.billing?", "list everything under
|java.util (recursive)?", "what annotations live in javax.persistence?". Cheap probe
|before code.get_source / code.list_members on each entry.
|
|Do NOT use this when:
|  - You want a module scope rather than a package scope — use code.list_classes_in_module.
|  - You want fuzzy / partial name matching — use JetBrains' search_symbol.
|  - The package doesn't exist (returns empty list, not an error — check `total`).
|
|Returns: { package, classes: ClassEntry[], total, truncated, timedOut }. Same
|ClassEntry shape as code.list_classes_in_module: fqn, simpleName, package, kind,
|fileUrl, declarationRange. Anonymous + inner classes excluded.
|
|Library scope: includeLibraries=false (default) means project-sources only. Enabling
|it widens the scope to GlobalSearchScope.allScope and can return tens of thousands of
|classes (rt.jar alone ~30k). Always combine includeLibraries=true with a narrow
|package and a tight limit.
|
|Examples:
|  packageFqn="com.acme.billing"                                — direct children, project sources
|  packageFqn="com.acme.billing", recursive=true                — whole subtree
|  packageFqn="java.util", includeLibraries=true, recursive=false — JDK util top level
|  packageFqn="", recursive=false                                — default/root package
```

## Args + response model

Add to `model/args/CodeArgs.kt` (new file — keeps `code.*` args isolated from
`ArchArgs.kt`, matching the per-group split already used for `PsiArgs`, `UiArgs`,
`ExecArgs`, `ScreenshotArgs`):

```kotlin
@Serializable
data class ListClassesInModuleArgs(
    val moduleName: String,
    val packagePrefix: String? = null,
    val includeTests: Boolean = false,
    val includeGenerated: Boolean = true,
    val kinds: List<String> = listOf("class", "interface", "enum", "record", "annotation", "kotlinFileFacade"),
    val limit: Int = 1000,
)

@Serializable
data class ListClassesInPackageArgs(
    val packageFqn: String,
    val recursive: Boolean = false,
    val includeLibraries: Boolean = false,
    val kinds: List<String> = listOf("class", "interface", "enum", "record", "annotation", "kotlinFileFacade"),
    val limit: Int = 500,
)
```

Append to `model/ClassSourceInfo.kt` (shared with existing `code.*` types):

```kotlin
@Serializable
data class DeclarationRange(val startOffset: Int, val endOffset: Int)

@Serializable
data class ClassEntry(
    val fqn: String,
    val simpleName: String,
    /** Package FQN. Empty string for default/root package. */
    val `package`: String,
    /** "class" | "interface" | "enum" | "record" | "annotation" | "kotlinFileFacade". */
    val kind: String,
    /** VFS URL of the declaring file (may be jar:// for library classes). */
    val fileUrl: String?,
    val declarationRange: DeclarationRange? = null,
)

@Serializable
data class ListClassesResponse(
    /** Echoes the moduleName arg (module variant) or packageFqn (package variant). */
    val scope: String,
    val classes: List<ClassEntry>,
    val total: Int,
    val truncated: Boolean,
    /** True when the 10 s hard cap fired before enumeration completed. Partial results
     *  in `classes` are still valid. */
    val timedOut: Boolean = false,
)
```

## IntelliJ APIs used

- `JavaPsiFacade.getInstance(project).findPackage(packageFqn)` → `PsiPackage`
  ([source](https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/JavaPsiFacade.java)).
- `PsiPackage.getClasses(GlobalSearchScope)` — top-level classes in this package only.
- `PsiPackage.subPackages` — for recursive walk.
- `ModuleManager.getInstance(project).findModuleByName(name)` — module lookup.
- `GlobalSearchScope.moduleScope(module)` — production + test source roots in module.
- `GlobalSearchScope.moduleWithLibrariesScope(module)` — not used here (always too wide
  for "classes in this module"). For `includeLibraries=false` we use `projectScope`,
  for `true` we use `allScope`.
- `ModuleRootManager.getInstance(module).fileIndex.iterateContent { vf -> … }` — VFS
  iteration when we need to filter test vs production at the file level (the
  `GlobalSearchScope` predicates above don't separate them).
- `ProjectFileIndex.isInTestSourceContent(vf)` / `isInGeneratedSourceContent(vf)` —
  to honour `includeTests` and `includeGenerated`.
- `PsiManager.getInstance(project).findFile(vf)` → `PsiFile`; cast to `PsiClassOwner`
  and read `.classes` to get every top-level `PsiClass` in the file (Kotlin's
  `KtFile` implements `PsiClassOwner` via light-class wrapping, so the same code
  handles `.java` and `.kt`).
- `PsiClass.qualifiedName` / `.name` / `.containingFile.virtualFile.url` /
  `.textRange` for ClassEntry fields.
- Kotlin file facade detection: `org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade`
  — `is` check, no hard import (use reflection or `Class.forName` guard so the JVM
  load still works in IDEs without the Kotlin plugin). When matched, set
  `kind="kotlinFileFacade"`.

**Stability**: `JavaPsiFacade`, `PsiPackage`, `ModuleManager`, `ProjectFileIndex`,
`GlobalSearchScope`, `PsiClassOwner` are all stable platform API. `KtLightClassForFacade`
is part of the Kotlin plugin's light-class API — accessed defensively so the tool
still works in pure-Java projects.

## Threading & EDT model

Pure PSI read — wrap everything in `ReadAction.compute<T>` (use the existing
`readActionBlocking` helper from `util/`, same as the other `code.*` methods). **No
EDT bouncing.** No Swing, no VFS write, no model modification.

No cache. Per-call cost is already cheap; caching by `(module, prefix, kinds, …)`
key is not worth the invalidation complexity given that source roots and packages
change on every edit.

## Timeout strategy

Hard 10 s cap (CLAUDE.md "Timeouts"). Mitigations to keep realistic worst cases under:

1. **`includeLibraries=false` by default** on the package variant — without this,
   `java.util` recursive would enumerate ~30k JDK classes. The description warns
   loudly.
2. **`limit` arg** clamped to `[1, 5000]` (`require(limit in 1..5000)`) — even
   when scope is huge, response stays small. Set `truncated=true` once limit hits.
3. **Wall-clock budget**: pass a deadline (`System.nanoTime() + 10_000_000_000L`) into
   the enumerator; check between files/packages and bail with `timedOut=true` plus
   partial results. The 10 s comes from `EdtHelpers.DEFAULT_EDT_TIMEOUT_MS` — reuse
   the constant rather than re-declaring it.
4. **VFS iteration over JavaPsiFacade for the module variant**:
   `iterateContent { vf -> … }` is O(files) and skips already-cached PSI; far cheaper
   than `psiPackage.getClasses(moduleScope)` for a wide package prefix.
5. **Defensive `runCatching`** per file/package so one corrupt PSI entry doesn't
   take the whole call down.

If a realistic worst case still exceeds 10 s, the right answer per CLAUDE.md is
"make it cheaper" — e.g. require a `packagePrefix` or split into a streaming follow-up
tool. Do NOT raise the cap.

## Edge cases

1. **Module not found** — return `McpExpectedError("Module not found: <name>. Use
   JetBrains' get_project_modules to list available modules.", …)`. Don't 500.
2. **Empty package / package not found** — return
   `ListClassesResponse(scope=packageFqn, classes=[], total=0, truncated=false)`.
   Not an error. Matches `arch.list_services` empty-result convention.
3. **Mixed Java + Kotlin module** — `PsiClassOwner.classes` returns Java classes
   from `.java` and light-class wrappers from `.kt` uniformly. No per-language
   branching needed for the basic shape.
4. **Kotlin file-level functions/properties** — Kotlin's compiler synthesizes a
   `FooKt` class at the file's top level for free functions / properties.
   Light-class wrapping surfaces this as a `PsiClass` whose runtime type is
   `KtLightClassForFacade`. Map to `kind="kotlinFileFacade"` so an agent can
   distinguish from a hand-written `class FooKt`. Skip these entirely if
   `"kotlinFileFacade"` is not in the `kinds` filter.
5. **Default/root package** — `packageFqn=""` is valid; `JavaPsiFacade.findPackage("")`
   returns the root. Document explicitly in the `@McpDescription`.
6. **Anonymous classes** — never returned. We only walk top-level `PsiClass`es from
   `PsiClassOwner.classes`; anonymous classes are nested in method bodies and
   wouldn't appear there anyway. Inner classes (`Outer.Inner`) are also excluded
   in v1 — see Open Qs.
7. **Generated sources** (`build/generated/`, `.gen/`, kapt output) — controlled
   by `includeGenerated` on the module variant. The package variant has no such
   knob (its scope is package-shaped, not directory-shaped); document this asymmetry.
8. **PsiClass without qualifiedName** — local / synthetic classes without an FQN.
   Skip silently; an entry with `fqn=null` is unusable for follow-ups.
9. **Dumb mode / indexing** — `JavaPsiFacade.findPackage` and `PsiPackage.getClasses`
   are dumb-mode-aware and may return stale or empty results during indexing. Surface
   a hint in the response when `DumbService.isDumb(project)` is true:
   `timedOut=false`, `truncated=false`, but include a `note: "Project is indexing;
   results may be incomplete"` field on the response (extend the model with an
   optional `note: String?`).
10. **Library scope = `allScope`** — when `includeLibraries=true` we use
    `GlobalSearchScope.allScope(project)`; for `false`, `projectScope` (sources only).
    Never use `everythingScope` (also includes non-Java VFS).
11. **Module with no source roots** — returns empty `classes` list, `total=0`,
    `truncated=false`. Not an error.

## Files to create/modify

NO new tool groups, NO new META-INF wiring. `code.*` already lives behind
`java-introspect.xml` which depends on `com.intellij.modules.java` — exactly the
condition the new tools need.

| Path | Op | What |
|------|----|------|
| `src/main/kotlin/.../tools/CodeSourceToolset.kt` | Edit | Add `code_list_classes_in_module` and `code_list_classes_in_package` methods (≈40 LOC + descriptions). |
| `src/main/kotlin/.../core/ClassCatalog.kt` | Create | Headless enumeration: `listInModule(project, args)` and `listInPackage(project, args)`. Pure PSI reads, no IDE-runtime deps beyond JavaPsiFacade / PsiPackage / ProjectFileIndex. Keeps `ClassSourceResolver` focused on single-FQN resolution (rejected alternative: bolt onto ClassSourceResolver — would conflate "resolve one" and "enumerate many"). |
| `src/main/kotlin/.../model/ClassSourceInfo.kt` | Edit | Append `ClassEntry`, `DeclarationRange`, `ListClassesResponse`. |
| `src/main/kotlin/.../model/args/CodeArgs.kt` | Create | `ListClassesInModuleArgs`, `ListClassesInPackageArgs`. New per-group args file. |
| `src/test/kotlin/.../core/platform/ClassCatalogPlatformTest.kt` | Create | `BasePlatformTestCase` fixture project with Java + Kotlin files across multiple packages, plus a generated-source directory. |
| `src/test/kotlin/.../core/ClassCatalogFilterTest.kt` | Create | Pure unit tests for kind filtering, packagePrefix matching, limit truncation. |

`docs/MCP_TOOLS.md` regenerates from the `@McpTool` / `@McpDescription` annotations
on the next `./gradlew compileKotlin` — do not hand-edit.

## Test plan

**Unit (`ClassCatalogFilterTest.kt`)** — pure JVM, no IntelliJ runtime:

- kind filter accepts allowed names, rejects unknowns silently (matches existing
  `code.list_members` behaviour).
- `packagePrefix=null` matches every FQN; `"com.acme"` matches `com.acme.*` but not
  `com.acmex`.
- `limit` clamp: invalid values throw `IllegalArgumentException`; oversize result
  trims to `limit` and sets `truncated=true`.
- `kotlinFileFacade` excluded when not in `kinds`.

**Platform (`ClassCatalogPlatformTest.kt`)** — extends `BasePlatformTestCase`, sets
up a fixture with two modules each containing Java + Kotlin sources:

- `testListInModuleReturnsTopLevelClassesOnly` — production only, no inner classes,
  no anonymous.
- `testListInModuleIncludeTests` — toggle returns test-root classes.
- `testListInModuleExcludesGeneratedWhenFlagFalse` — generated sources hidden by
  `includeGenerated=false`.
- `testListInModuleHonoursPackagePrefix` — only `com.acme.billing.*` returned.
- `testListInModuleKindFilterAnnotationOnly` — kinds=["annotation"] returns only
  annotations.
- `testListInPackageNonRecursive` — direct children only.
- `testListInPackageRecursive` — full sub-tree.
- `testListInPackageIncludeLibrariesFalseExcludesJdk` — `java.util` returns empty in
  a project-sources-only listing.
- `testListInPackageDefaultPackage` — `packageFqn=""` works.
- `testKotlinFileFacadeKind` — a `.kt` file with top-level `fun foo()` produces a
  ClassEntry with `kind="kotlinFileFacade"`.
- `testModuleNotFoundThrowsMcpExpectedError` — `moduleName="nope"` raises the
  expected error type, not an uncaught.
- `testLimitTruncates` — synthetic fixture with > limit classes returns
  `truncated=true`.
- `testTimedOutFlagWhenDeadlineExceeded` — inject a very small deadline (via test
  hook or `Thread.sleep` in a `runCatching` block) and verify `timedOut=true`
  with partial results.

The `@McpTool` wrappers on `CodeSourceToolset` are thin enough not to need their own
test class — `ClassCatalog`'s tests cover the logic.

## Estimated effort

| Step | Hours |
|------|-------|
| `ClassEntry` + `ListClassesResponse` models + `CodeArgs.kt` | 0.5 |
| `ClassCatalog.kt` (both methods, deadline plumbing, kotlinFileFacade detection) | 3 |
| Two `@McpTool` wrappers + `@McpDescription` polishing | 1 |
| Unit tests | 1 |
| Platform tests (multi-module fixture is the slow bit) | 2 |
| Doc-gen verification + `runIde` smoke against a real Java + Kotlin project | 0.5 |
| **Total** | **~1 day** |

## Open questions / risks

1. **Inner classes** — v1 excludes them (top-level only). Adding an
   `includeInnerClasses: Boolean = false` flag is cheap (`PsiClass.innerClasses`
   walk), but inflates response size and complicates `fqn` (uses `$`). **Defer to v2
   under explicit demand.** Document explicitly in the `@McpDescription`.
2. **Per-class size metric** (lines / bytes) so an agent can prioritize the
   biggest classes when investigating. Cheap to add (`PsiFile.textLength` for line
   count is one extra read per class). **Recommend: add `byteLength: Int?` to
   ClassEntry in v1** — costs nothing for the module variant (we already touch
   every file) and helps the agent budget follow-up `code.get_source` calls.
3. **Kotlin object / companion object kinds** — currently surface as `kind="class"`
   via light-class wrapping. A separate `kind="kotlinObject"` /
   `kind="kotlinCompanion"` would help an agent distinguish, but requires a Kotlin-
   plugin-only reflective check. **Defer.** Note in the description.
4. **`fileUrl` per class** — recommended **yes**, included in v1. Makes
   `code.get_source` follow-ups one round-trip cheaper and lets an agent jump
   straight to a file without an extra `code.find_class` call.
5. **Deadline injection** — making `ClassCatalog` testable for `timedOut=true`
   without `Thread.sleep` flakiness needs a `Clock`/`Deadline` seam. Mirror what
   `ExecToolset` does with `ExecSettings`. Small refactor; budgeted above.
6. **Library scope edge cost** — `includeLibraries=true` on the package variant
   without a tight `packageFqn` can hit the 10 s cap. The `truncated=true` +
   `timedOut=true` response is a correct outcome, but document loudly in the
   `@McpDescription` so the agent doesn't naively retry the same call.
7. **PyCharm CE / GoLand** — `java-introspect.xml` only loads when
   `com.intellij.modules.java` is present, so these tools naturally don't appear
   in non-Java IDEs. No additional gating needed.

## References

- Existing code:
  - `tools/CodeSourceToolset.kt#code_find_class` — same read-action pattern, same
    `requireProject()` / `McpExpectedError` shape.
  - `core/ClassSourceResolver.kt` — sibling headless helper; new `ClassCatalog.kt`
    follows the same `object` + pure-function layout.
  - `core/internal/TtlCache.kt` — not used here, but referenced if a follow-up
    decides to cache per-module catalogs.
  - `core/PluginInventory.kt` — pattern for "enumerate-many, defensively per-entry
    with `runCatching`".
- IntelliJ source:
  - `JavaPsiFacade`: https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/JavaPsiFacade.java
  - `PsiPackage`: https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/PsiPackage.java
  - `GlobalSearchScope`: https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/search/GlobalSearchScope.java
  - `ProjectFileIndex`: https://github.com/JetBrains/intellij-community/blob/master/platform/projectModel-api/src/com/intellij/openapi/roots/ProjectFileIndex.kt
  - `KtLightClassForFacade` (Kotlin plugin, optional): https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/base/light-classes/src/org/jetbrains/kotlin/asJava/classes/KtLightClassForFacade.kt
- JetBrains MCP equivalent: `search_symbol` (fuzzy class/method/field name lookup —
  different shape: ranks fuzzy matches, doesn't enumerate a scope). These tools are
  the strict-by-scope complement, not a replacement.
