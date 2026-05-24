# `arch.check_lock_requirements` + `arch.check_threading_requirements`

## Purpose & motivation

Two static-analysis tools mirroring JetBrains' DevKit MCP `find_lock_requirement_usages`
and `find_threading_requirements_usages`. Given a target method, both enumerate every
call site and check whether the caller statically satisfies the same
`com.intellij.util.concurrency.annotations.*` annotation the target carries. The IDE
Introspector audience IS plugin developers, so these checks belong in the kit even though
DevKit's MCP server already ships them — DevKit isn't loaded in every IDE flavor (IDEA
Community without DevKit, RustRover, GoLand, …), and our version piggy-backs on the PSI
infrastructure already built for `psi.find_usages` so behaviour stays consistent.

Success criterion: with the cursor on (or the FQN of) a method, the agent can list every
caller that violates the method's `@RequiresReadLock` / `@RequiresEdt` contract without
launching `exec.execute_kotlin_in_ide` or relying on DevKit.

## Tool specifications

Both tools share signature shape, args class shape, and response model — only the
annotation set under analysis differs. The signature block below covers
`arch.check_lock_requirements`; `arch.check_threading_requirements` is identical with
the parameter named `target` etc., just registered with the threading description.

### `arch.check_lock_requirements`

**Signature:**
```kotlin
@McpTool(name = "arch.check_lock_requirements")
@McpDescription(/* see below */)
suspend fun arch_check_lock_requirements(
    @McpDescription("FQN.method (e.g. 'com.intellij.psi.PsiManager.findFile'). Mutually exclusive with fileUrl+position.")
    target: String? = null,
    @McpDescription("VFS URL of the source file containing the target method. Use with offset OR line+column.")
    fileUrl: String? = null,
    @McpDescription("Document offset on the method's name. Alternative to line+column.")
    offset: Int? = null,
    @McpDescription("1-based line number of the method's name.")
    line: Int? = null,
    @McpDescription("1-based column number of the method's name.")
    column: Int? = null,
    @McpDescription("\"project\" (default) / \"file\" / \"all\" (library sources — slow).")
    scope: String = "project",
    @McpDescription("Also analyse callers of overrides via DefinitionsScopedSearch. Default true.")
    includeImplementations: Boolean = true,
    @McpDescription("Hard cap on returned call sites. Default 500.")
    maxCallSites: Int = 500,
): CheckRequirementsResponse
```

**`@McpDescription` (verbatim, trim-margin):**
```
|Statically verifies that every caller of a method holds the IntelliJ read/write lock
|the method requires. The target's @RequiresReadLock / @RequiresWriteLock /
|@RequiresReadLockAbsence is the contract; each caller is checked by walking to the
|enclosing method/lambda: does it carry the same annotation (transitive), or is it
|lexically inside ReadAction.compute / runReadAction / WriteAction.run / runWriteAction?
|Mirror of DevKit's `find_lock_requirement_usages` — works in IDEs without DevKit.
|
|Use this when: "is foo() always called under a read lock?", "who calls bar() without a
|write action?", or before changing a method's lock contract.
|
|Do NOT use this when: you want runtime/dynamic checks (static — Runnable posted to
|invokeLater is `unknown`, not `ok`), or the target has no annotation (response is
|trivially empty). For "who calls foo()?" without lock analysis use psi.find_usages.
|
|Target: pass `target` as `FQN.method` (overload-ambiguous; every method with that
|simple name is included), OR `fileUrl` + offset / line+column on the method
|declaration — same position semantics as psi.find_usages.
|
|Returns: { target, expected[], callSites[], total, truncated }. CallSiteAnalysis has
|fileUrl, range, callerSignature, callerAnnotations[], contextHints[], status
|('ok'|'mismatch'|'unknown'), reason. `unknown` = we can't reason statically
|(invokeLater, reflection, coroutine builders) — agent should escalate to manual review.
|
|Examples:
|  target="com.intellij.psi.PsiManager.findFile"                          — all callers
|  fileUrl=null, line=42, column=12                                        — method at row 42 in active editor
|  target="com.example.MyService.doStuff", scope="file"                    — same-file only
|  target="com.example.MyService.doStuff", includeImplementations=false    — direct calls only
```

### `arch.check_threading_requirements`

Same signature shape. Annotation set: `@RequiresEdt` / `@RequiresBackgroundThread` /
`@RequiresBlockingContext`. Recognised wrappers: `ApplicationManager.invokeLater` /
`invokeAndWait`, `SwingUtilities.invokeLater` (EDT-pushing), `executeOnPooledThread`
(BGT-pushing). Anything not statically resolvable → `status="unknown"`.

**`@McpDescription` (verbatim, trim-margin):**
```
|Statically verifies that every caller of a method runs on the thread the method
|requires. The target's @RequiresEdt / @RequiresBackgroundThread /
|@RequiresBlockingContext annotation is the contract; each caller is checked by walking
|to the enclosing method/lambda and asking: same annotation (transitive), or lexically
|inside ApplicationManager.invokeLater / SwingUtilities.invokeLater (EDT-pushing) /
|executeOnPooledThread (BGT-pushing)? Mirror of DevKit's
|`find_threading_requirements_usages` — works in IDEs without DevKit loaded.
|
|Use this when: "is foo() always called from a BGT?", "who calls X from the EDT?", or
|before tightening a threading contract.
|
|Do NOT use this when: you want runtime checks (ApplicationManager.isDispatchThread()),
|or the target has no threading annotation (response trivially empty). For callers
|without thread context, use psi.find_usages.
|
|Target: pass `target` as `FQN.method`, OR `fileUrl` + offset / line+column on the
|method's name — same position semantics as psi.find_usages.
|
|Returns: { target, expected[], callSites[], total, truncated }. CallSiteAnalysis
|has callerSignature, callerAnnotations[], contextHints[] (['inside-invokeLater']),
|status ('ok'|'mismatch'|'unknown'), reason. Lambdas in opaque Runnable consumers
|(Future, ExecutorService, kotlinx coroutines) → 'unknown'.
|
|Examples:
|  target="com.intellij.openapi.editor.Editor.getCaretModel"                — @RequiresEdt callers
|  fileUrl="…/MyService.kt", line=20, column=5, scope="file"                — one-file scope
|  target="com.example.Backend.doWork", includeImplementations=false        — direct calls only
```

## Shared args + response

Two distinct args classes (so KSP renders two tool docs without aliasing):

```kotlin
// model/args/ArchArgs.kt
@Serializable
data class CheckLockRequirementsArgs(
    val target: String? = null, val fileUrl: String? = null,
    val offset: Int? = null, val line: Int? = null, val column: Int? = null,
    val scope: String = "project", val includeImplementations: Boolean = true,
    val maxCallSites: Int = 500,
)
// CheckThreadingRequirementsArgs — identical field list.

// model/RequirementsInfo.kt (new)
@Serializable enum class RequirementKind { READ_LOCK, WRITE_LOCK, NO_READ_LOCK, EDT, BGT, BLOCKING_CONTEXT }
@Serializable data class RequirementAnnotation(val kind: RequirementKind, val fqn: String)
@Serializable data class CallSiteAnalysis(
    val fileUrl: String, val range: TextRangeInfo,    // reuse PsiStructureWalker.textRangeInfoOf
    val callerSignature: String,                       // "com.example.Foo.bar(Project)"
    val callerAnnotations: List<RequirementAnnotation>,
    val contextHints: List<String>,                    // ['inside-runReadAction']
    val status: String,                                 // "ok" | "mismatch" | "unknown"
    val reason: String,
)
@Serializable data class CheckRequirementsResponse(
    val target: TargetInfo,                             // reuse model.TargetInfo
    val expected: List<RequirementAnnotation>,
    val callSites: List<CallSiteAnalysis>,
    val total: Int, val truncated: Boolean,
)
```

Validation: exactly one of `target` OR `fileUrl+position` supplied; else
`McpExpectedError("specify target OR fileUrl+position")`.

## Implementation notes

- New `core/RequirementsAnalyzer.kt` holds all logic. Pure object, no IDE singletons.
- Target resolution: FQN → split on last `.`, `JavaPsiFacade.findClass(fqn, allScope)`,
  enumerate matching simple-name overloads. Positional → re-use `PsiToolset.resolveOffset`,
  extracted to `util/PositionResolver.kt` and shared.
- Call-site search: re-use `PsiUsageSearcher`. Add `callersOf(target, scope,
  includeImplementations, max)` returning `List<PsiReference>` — same `ReferencesSearch`
  path `psi.find_usages` already runs.
- Per reference: enclosing scope via `PsiTreeUtil.getParentOfType(ref.element,
  PsiMethod, PsiLambdaExpression, KtNamedFunction, KtLambdaExpression)`. Walk its
  annotations. Walk parents for recognised wrappers (lock: `ReadAction.run/compute`,
  `runReadAction`, `runWriteAction`; threading: `invokeLater`, `invokeAndWait`,
  `executeOnPooledThread`, `Dispatchers.EDT`). Match by callee FQN — no data-flow.
- Status decision tree:
  - No target annotations → short-circuit, reason "no contract".
  - Enclosing has same annotation → `ok`.
  - Enclosing lexically inside a recognised wrapper → `ok` (wrapper → `contextHints`).
  - Lambda in opaque dispatcher → `unknown`.
  - Caller `@RequiresWriteLock` on target `@RequiresReadLock` → `ok` (write subsumes);
    target `@RequiresReadLockAbsence` + caller `@RequiresReadLock` → `mismatch`.
  - Otherwise → `mismatch`, reason "no compatible annotation".
- Caller signature: `"${class.qualifiedName}.${name}(${params})"`; lambdas →
  `"$enclosingMethod.<lambda@line>"`.

## Threading, EDT, timeout

`arch.*` group; no Swing access. Full pipeline (target resolution + ReferencesSearch +
per-ref annotation walk) runs in `readActionBlocking { ... }` (10 s cap) inside
`DumbService.computeWithAlternativeResolveEnabled` — same as `psi.find_usages`. On
timeout, PCE cancellation aborts and we surface `truncated=true` with whatever's been
decorated. Per-ref enclosing-scope walk is O(parent chain) — bounded. Defaults:
`maxCallSites=500`, `scope="project"` (NOT `"all"`).

## Edge cases

1. Target with no annotations → `expected=[]`, no analysis, reason "no contract".
2. Call site in a lambda handed to `invokeLater` / `executeOnPooledThread` / opaque
   `Runnable` consumer → `unknown`, reason names the dispatcher.
3. Java vs Kotlin caller: Java uses `PsiModifierListOwner.getModifierList()`; Kotlin
   uses `KtAnnotated.annotationEntries`. Analyzer branches on `PsiMethod` vs `KtElement`.
4. Method overrides with `includeImplementations=true`: also include overrides
   (DefinitionsScopedSearch); their callers are checked against the *interface*
   method's contract.
5. DevKit-bundled vs `org.jetbrains.annotations` analogs: `RequiresReadLock` etc. live
   in `com.intellij.util.concurrency.annotations`. Hard-code (see open Qs).
6. Class-level annotation: only walk up to the containing class if the method itself
   has no annotation; method-level wins.
7. Unknown FQN → `McpExpectedError("No class found for $fqn — checked allScope")`.
8. Dumb mode: `DumbService.computeWithAlternativeResolveEnabled` (same as
   `psi.find_usages`). Partial results → `truncated=true`.
9. Recursive call: caller inherits annotation, status `ok`.
10. `@RequiresReadLockAbsence`: `mismatch` if caller is `@RequiresReadLock`/
    `@RequiresWriteLock` or lexically inside a `ReadAction`/`WriteAction`.

## Files to create/modify

| Path | Op | What |
|------|----|------|
| `src/main/kotlin/.../core/RequirementsAnalyzer.kt` | Create | annotation set, status decision tree, wrapper recognition |
| `src/main/kotlin/.../core/PsiUsageSearcher.kt` | Edit | extract `callersOf(target, scope, includeImpls, max)` |
| `src/main/kotlin/.../util/PositionResolver.kt` | Create | extract `resolveOffset` from PsiToolset for sharing |
| `src/main/kotlin/.../tools/PsiToolset.kt` | Edit | use shared PositionResolver (no behaviour change) |
| `src/main/kotlin/.../model/RequirementsInfo.kt` | Create | response model |
| `src/main/kotlin/.../model/args/ArchArgs.kt` | Edit | add two arg classes |
| `src/main/kotlin/.../tools/ArchitectureToolset.kt` | Edit | two `@McpTool` methods, thin wrappers |
| `src/test/kotlin/.../core/platform/RequirementsAnalyzerPlatformTest.kt` | Create | platform tests |
| `src/test/testData/requirements/` | Create | Kotlin + Java fixture sources |

No new META-INF wiring — registered via the existing `ArchitectureToolset` entry in
`mcp-integration.xml`.

## Test plan

Platform tests (`BasePlatformTestCase`, same pattern as other `core/platform/*Test.kt`).

- **A — Java `@RequiresReadLock`:** `Good.caller` annotated → `ok`; `Bad.caller` plain
  → `mismatch`; lambda inside `ReadAction.run(() -> svc.load())` → `ok`
  (`contextHints=['inside-ReadAction.run']`); lambda inside `invokeLater { svc.load() }`
  → `unknown`.
- **B — Kotlin `@RequiresEdt`:** annotated caller → `ok`; plain caller → `mismatch`;
  lambda inside `invokeLater { … }` → `ok` (EDT-pushing).
- **C — interface + override:** `Repo.all()` carries `@RequiresReadLock`; with
  `includeImplementations=true` direct `RepoImpl.all()` calls also checked against
  `Repo.all`'s contract.
- **D — `@RequiresReadLockAbsence`:** caller in `runReadAction { target() }` → `mismatch`.
- **E — no annotation:** `expected=[]`, zero sites, reason "no contract".
- **F — unknown FQN:** `McpExpectedError`.

Plus tiny unit tests on `RequirementsAnalyzer` helpers (signature renderer, wrapper-call
recogniser).

## Estimated effort

~1.5 days. 0.25 d — `PositionResolver` refactor + `PsiUsageSearcher.callersOf`.
0.5 d — `RequirementsAnalyzer` (decision tree + wrappers + signatures). 0.25 d —
toolset methods + arg validation + `@McpDescription` polish. 0.5 d — fixtures +
platform tests (the meat: six fixtures, two languages, every status branch).

## Open questions

1. **Annotation FQN source**: depend on
   `org.jetbrains.idea.devkit.threading.RequiresReadLockAnnotationProvider` (pulls a
   DevKit dep we explicitly want to avoid), or hard-code the six FQNs? Hard-coding
   wins — six constants, stable since 2019, keeps DevKit-free.
2. **`kotlinx.coroutines` dispatchers**: `Dispatchers.Main.immediate` /
   `Dispatchers.EDT` inside `launch` / `withContext` — recognising those catches a
   whole class of modern Kotlin code. Out of scope for v1; document as a known
   false-`unknown` source in `@McpDescription`.
3. **`@RequiresBlockingContext`**: flags methods that must NOT be called from a
   coroutine. Detecting "inside `suspend fun` / coroutine builder" needs
   Kotlin-specific PSI handling — v1 only checks the annotation on the caller.

## References

- Existing: `core/PsiUsageSearcher.kt`, `tools/PsiToolset.kt#psi_find_usages` (position
  resolution + dumb-mode handling).
- DevKit equivalents: `find_lock_requirement_usages`,
  `find_threading_requirements_usages` (only registered when DevKit plugin is loaded).
- Annotations: `com.intellij.util.concurrency.annotations.*` — IntelliJ Community
  `platform/util/concurrency/src/com/intellij/util/concurrency/annotations/`.
