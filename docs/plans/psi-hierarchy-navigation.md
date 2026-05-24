# `psi.type_hierarchy` + `psi.goto_implementation`

## Purpose & motivation

Two PSI navigation tools filling gaps in `psi.*` AND JetBrains' MCP server.
`psi.type_hierarchy` mirrors IntelliJ's Ctrl+H Hierarchy window — given a class
(FQN or position), return its supertype / subtype tree.
`psi.goto_implementation` mirrors Ctrl+Alt+B — given a position on an
interface, abstract class, or method, return concrete implementors / overrides.
`psi.find_usages` with `includeImplementations=true` partially overlaps goto-impl
but lumps overrides into a usages list, has no FQN entry point, and returns no
multi-level tree.

**Success criterion**: an agent answers "supertype chain of `Editor`?" and
"concrete impls of `FileEditorProvider`?" in one MCP call each.

## Tool specification

### `psi.type_hierarchy`

```kotlin
@McpTool(name = "psi.type_hierarchy")
@McpDescription("""…verbatim below…""")
suspend fun psi_type_hierarchy(
    target: String? = null,                 // FQN; takes precedence over position
    fileUrl: String? = null,                // null → active editor tab
    offset: Int? = null,                    // OR line+column
    line: Int? = null, column: Int? = null,
    direction: String = "both",             // "up" | "down" | "both"
    scope: String = "project",              // "file" | "project" | "all"
    maxDepth: Int = 5, maxNodes: Int = 200,
): TypeHierarchyResponse
```
Per-parameter `@McpDescription` strings are concise (one line each, matching
the descriptions in the args table further down).


`@McpDescription` (verbatim — trim-margin, the reflection bridge strips margins):

```
|Returns the type hierarchy of a class — supertypes (parents) and/or subtypes
|(implementors / extenders) — as a tree rooted at the target. Mirrors IntelliJ's
|Hierarchy tool window (Ctrl+H, "Type Hierarchy").
|
|Use this when:
|  - The agent needs what a class extends / implements ("up").
|  - The agent needs who extends or implements a class ("down").
|  - A multi-level tree is more useful than a flat list.
|  - Sealed-type exhaustiveness check — direct subtypes included, `isSealed` flagged.
|
|Do NOT use this when:
|  - You only need concrete impls of an interface / abstract member —
|    psi.goto_implementation is more focused and returns method signatures.
|  - You want references / call sites — that is psi.find_usages.
|
|Target: pass `target` (FQN, takes precedence) OR a position (fileUrl + offset
|OR line+column) on a class decl / reference. Anonymous + local classes are
|recognised at a position but never appear as subtype nodes (no FQN).
|
|Scope (default "project"): "file" (rare), "project" (default), "all" (includes
|library sources — for hot types like java.util.List or java.lang.Object the
|subtype walk can saturate the 10s read-action timeout; a warning is appended).
|
|Caps: maxDepth (5) and maxNodes (200) bound the walk. On a cap, `truncated`
|is set and the cut branch's leaf carries `childrenTruncated=true`.
|
|Returns: { target: HierarchyClassRef, supertypes: HierarchyNode?, subtypes:
|HierarchyNode?, direction, scope, truncated, warnings[] }. Each HierarchyNode
|has `node` + `children[]` (parents for supertypes, child classes for subtypes).
|java.lang.Object is included as supertype root when walking "up" but its
|subtype walk is always rejected (would be the world).
|
|Examples:
|  target="com.intellij.openapi.editor.Editor"         — both directions, project
|  target="java.util.List", direction="up"             — super-interfaces only
|  fileUrl=null, line=42, column=14, direction="down"  — subtypes under caret
|  target="com.acme.Sealed", direction="down"          — exhaustive sealed list
```

### `psi.goto_implementation`

```kotlin
@McpTool(name = "psi.goto_implementation")
@McpDescription("""…verbatim below…""")
suspend fun psi_goto_implementation(
    fileUrl: String? = null,            // null → active editor tab
    offset: Int? = null,                // OR line+column
    line: Int? = null, column: Int? = null,
    scope: String = "project",          // "file" | "project" | "all"
    maxResults: Int = 200,
): GotoImplementationResponse
```

`@McpDescription` (verbatim):

```
|Returns every concrete implementation / override of the symbol at a given
|position — interfaces and abstract classes resolve to their concrete extenders,
|abstract / interface methods resolve to their concrete overrides. Equivalent
|to IntelliJ's Ctrl+Alt+B "Goto Implementation".
|
|Use this when:
|  - You see an interface or abstract method and need the concrete implementors.
|  - The agent is tracing a call graph through an abstraction boundary.
|  - You want a focused answer to "what overrides this method?" without the
|    noise of usages / reference sites that psi.find_usages would return.
|
|Do NOT use this when:
|  - You want call sites of a method — use psi.find_usages.
|  - You want a multi-level type tree — use psi.type_hierarchy.
|  - The caret is on a concrete final method — there are no overrides.
|
|Position: pass `offset` OR `line`+`column`. The caret may be on a class /
|interface declaration or reference (returns subclasses / implementors), or on
|a method declaration / call site (returns overriding methods). Reported in
|`target.kind` ("class" | "method").
|
|Scope (default "project"):
|  - "project" — project sources only. What Ctrl+Alt+B uses by default.
|  - "all"     — includes library sources; on a JDK / platform symbol this can
|                saturate the 10s read-action timeout. Warning is appended.
|  - "file"    — same file only.
|
|Returns: { target: ImplementationTarget, implementations: ImplementationInfo[],
|scope, total, truncated, warnings[] }. Sorted by (fileUrl, range). For method
|targets `signature` uses the *erasure* shown in the overrider's source —
|generic substitution is not normalised across interface/impl boundaries.
|
|Examples:
|  fileUrl=null, line=10, column=18         — overrides of method at row 10
|  fileUrl=null, line=5,  column=12         — implementors of interface name on row 5
|  scope="all", maxResults=50               — include library overrides, capped
```

## Args + response models

`model/args/PsiArgs.kt` (append):

```kotlin
@Serializable data class TypeHierarchyArgs(
    val target: String? = null, val fileUrl: String? = null,
    val offset: Int? = null, val line: Int? = null, val column: Int? = null,
    val direction: String = "both", val scope: String = "project",
    val maxDepth: Int = 5, val maxNodes: Int = 200,
)
@Serializable data class GotoImplementationArgs(
    val fileUrl: String? = null, val offset: Int? = null,
    val line: Int? = null, val column: Int? = null,
    val scope: String = "project", val maxResults: Int = 200,
)
```

`model/PsiInfo.kt` (append; all `@Serializable`):

```kotlin
data class HierarchyClassRef(val fqn: String?, val psiClass: String,
    val fileUrl: String? = null, val declarationRange: TextRangeInfo? = null,
    val isInterface: Boolean = false, val isAbstract: Boolean = false,
    val isFinal: Boolean = false, val isSealed: Boolean = false,
    val modifiers: List<String> = emptyList())
data class HierarchyNode(val node: HierarchyClassRef,
    val children: List<HierarchyNode> = emptyList(),
    val childrenTruncated: Boolean = false)
data class TypeHierarchyResponse(val target: HierarchyClassRef,
    val supertypes: HierarchyNode? = null, val subtypes: HierarchyNode? = null,
    val direction: String, val scope: String, val truncated: Boolean = false,
    val warnings: List<String> = emptyList())
data class ImplementationTarget(val name: String?, val psiClass: String,
    val kind: String /* "class" | "method" */, val fileUrl: String? = null,
    val declarationRange: TextRangeInfo? = null,
    val isAbstract: Boolean = false, val isInterface: Boolean = false)
data class ImplementationInfo(val fileUrl: String, val range: TextRangeInfo,
    val lineSnippet: String, val declaringClassFqn: String? = null,
    val signature: String? = null /* null when kind="class" */,
    val isAbstract: Boolean = false, val isOverride: Boolean = false)
data class GotoImplementationResponse(val target: ImplementationTarget,
    val implementations: List<ImplementationInfo>, val scope: String,
    val total: Int, val truncated: Boolean = false,
    val warnings: List<String> = emptyList())
```

Validation: `direction` ∈ {up,down,both}; `scope` ∈ {file,project,all};
`maxDepth` 1..20; `maxNodes`/`maxResults` 1..5_000. Invalid → `McpExpectedError`.

## IntelliJ APIs used (Java + Kotlin paths)

| Need | API | Notes |
|------|-----|-------|
| FQN → class | `JavaPsiFacade.getInstance(project).findClass(fqn, allScope)` | stable |
| Supertypes | `PsiClass.getSupers(): Array<PsiClass>`, recurse for depth | stable |
| Subtypes | `ClassInheritorsSearch.search(psiClass, scope, /*checkDeep=*/false)` | same query Hierarchy uses |
| Method overrides | `OverridingMethodsSearch.search(psiMethod, scope, true)` | stable |
| Class impls | `DefinitionsScopedSearch.search(element, scope, true)` | backs Ctrl+Alt+B |
| Scope | `GlobalSearchScope.{fileScope,projectScope,allScope}` / `LocalSearchScope` | stable |
| Modifiers | reuse `core/PsiModifiers.kt` | local |

**Kotlin uniformity**: `KtClass` / `KtClassOrObject` / `KtNamedFunction` are
exposed to all the above via the platform light-class adapters (`KtLightClass`,
`KtLightMethod`) — no Kotlin-plugin link-time dep. Sealed detection: simple-name
probe on `"KtClass"` for `SEALED_KEYWORD` plus `PsiClass.hasModifierProperty("sealed")`
for Java 17+ sealed — same no-link-time-dep pattern as `PsiUsageSearcher.isLocalVariableLike`.
Position resolution reuses `PsiToolset.resolveFile` / `resolveOffset` and
`PsiUsageSearcher.resolveTarget`'s follow-ref-or-named-ancestor walk, restricted
to `PsiClass` (type_hierarchy + class-mode goto_impl) or `PsiMethod`
(method-mode goto_impl).

## Threading & timeout

No EDT. Both run inside `readActionBlocking { … }` and wrap each search in
`DumbService.computeWithAlternativeResolveEnabled<R, RuntimeException> { … }` —
same as `psi_find_usages`. All `*Search` APIs respect the read-action context
and check PCE per result, so the 10 s readAction cap propagates cleanly. No
caching (state-dependent).

Hard 10 s cap (CLAUDE.md). Risky surface: `ClassInheritorsSearch` /
`DefinitionsScopedSearch` / `OverridingMethodsSearch` on a hot interface in
`scope="all"` (`List`, `Object`, `Object.toString()`) saturates the budget.
Mitigations: default `scope="project"` (matches IntelliJ Hierarchy default);
hard caps `maxNodes=200` / `maxResults=200` trip `truncated=true`; `scope="all"`
appends a warning even on success so the agent learns to narrow on follow-ups;
`java.lang.Object` subtype walk rejected with warning; per-result PCE check in
each `Query.forEach(Processor { … })` stops the moment a cap trips. If a caller
genuinely needs more than 10 s, the answer is narrower scope or paging — not a
higher timeout.

## Edge cases

1. **FQN does not resolve** → `McpExpectedError("No class found for FQN: …")`.
2. **Position not on a class/method** → `McpExpectedError("Caret is not on a class/method")`.
3. **`final` class target** — empty subtypes, single-node tree, no warning.
4. **`java.lang.Object` target** — subtype walk rejected with warning; supertype walk trivial.
5. **Anonymous / local classes** — recognised at position (`fqn=null`); excluded from subtype results.
6. **Kotlin objects (singletons)** — `KtLightClass` final → handled like any final class.
7. **Sealed class / sealed interface** — detected via `SEALED_KEYWORD` on `KtClass` (simple-name probe)
   OR `PsiClass.hasModifierProperty("sealed")`. Set `isSealed=true` + warning ("sealed — children exhaustive").
   Children still come from `ClassInheritorsSearch`; no special walk.
8. **Method on generic interface** — overrides surface with overrider's erasure signature; no cross-boundary unification.
9. **Final method** — empty overrides, not an error.
10. **Dumb mode** — `computeWithAlternativeResolveEnabled` covers most; surviving `IndexNotReadyException` → warning.
11. **Multi-resolve under caret** — follow first non-null target (matches `PsiUsageSearcher.resolveTarget`); warn with skipped count.
12. **Library-only target** — `target.fileUrl` is `jar://…`; `scope="project"` finds project subclasses only.

## Files to create/modify

- `core/PsiHierarchyResolver.kt` (Create) — headless logic for both tools:
  FQN/position → PsiClass/PsiMethod, super/sub walk, override search, result shaping.
- `model/PsiInfo.kt` (Edit) — append the six new `@Serializable` types.
- `model/args/PsiArgs.kt` (Edit) — append `TypeHierarchyArgs`, `GotoImplementationArgs`.
- `tools/PsiToolset.kt` (Edit) — add `psi_type_hierarchy` + `psi_goto_implementation`
  (thin wrappers).
- `src/test/kotlin/.../core/PsiHierarchyResolverTest.kt` (Create) — unit tests.
- `src/test/kotlin/.../core/platform/PsiHierarchyResolverPlatformTest.kt` (Create) —
  platform tests with Java + Kotlin fixtures.

No new XML wiring: both methods live on `PsiToolset` (already in
`mcp-integration.xml`). `JavaPsiFacade` requires `com.intellij.modules.java`; in
IDEs without it, surface a clean `McpExpectedError` rather than CNFE — split to
`java-introspect.xml` if CI shows breakage.

## Test plan

**Unit** — pure JVM: arg validation (`direction`/`scope`/bounds), truncation
flags on supertype + subtype caps, `Object` FQN → subtype rejected + warning,
position requires offset OR (line+column), FQN beats position when both, signature
normaliser produces `"R name(P p)"`.

**Platform** — `BasePlatformTestCase`, fixtures under `src/test/testData/psi/hierarchy/`:
`Animal.java` (abstract) ← `Dog`, `Cat` ← `Puppy`; `Greeter.java` (interface) ←
`EnglishGreeter`, `FrenchGreeter`; `SealedShape.kt` (sealed) ← `Circle`,
`Square`; `KtAnimal.kt` (Kotlin mirror). Tests:

- type_hierarchy: byFqn Animal returns extenders; byPosition caret-on-Dog returns
  Animal supertype; bothDirections Dog returns parents+children; maxDepth=1
  truncates (sets `childrenTruncated`); maxNodes=2 truncates (sets `truncated`);
  sealed flags exhaustive; Kotlin abstract works via KtLightClass; Object
  subtype rejected; final class returns empty subtypes.
- goto_implementation: on interface Greeter returns two impls (`kind="class"`);
  on abstract method returns concrete overrides (`kind="method"`); on final
  method empty; `scope="all"` always appends warning.

## Estimated effort

Models 0.5h, supertype + subtype walk + caps 3h, goto-impl (class + method) +
signature norm 2h, toolset methods + `@McpDescription` 1h, unit tests 1.5h,
Java + Kotlin fixtures + platform tests 3h, doc-gen + `runIde` smoke 1h. Total
**~1.5 days (12 h)**.

## Open questions / risks

1. **goto_implementation on a class — follow `extends` AND `implements`?**
   `DefinitionsScopedSearch` natively does both. **Default: yes**;
   `declaringClassFqn` lets callers filter.
2. **Walk Kotlin sealed children automatically?** Sealed children fall out of
   `ClassInheritorsSearch` naturally — no special walk; just flag `isSealed=true`
   + warning so the agent knows the list is exhaustive.
3. **Deduplicate impls overridden further down?** `OverridingMethodsSearch(checkDeep=true)`
   returns BOTH abstract mid-tier and concrete leaves. **Proposed: filter
   `!isAbstract` for methods by default**; add `includeAbstract` flag only if a
   caller demonstrates need (not in v1).
4. **`OverridingMethodsSearch` order** — no platform stability promise; we sort
   by `(fileUrl, range.startOffset)`.
5. **Java module dep** — `JavaPsiFacade` CNFE on IDEs without
   `com.intellij.modules.java` → degrade with clean error; split to
   `java-introspect.xml` if CI insists.

## References

Existing: `tools/PsiToolset.kt#psi_find_usages` (positional + scope + truncation
pattern); `core/PsiUsageSearcher.kt` (`DefinitionsScopedSearch` + `resolveTarget`);
`core/PsiModifiers.kt`. IntelliJ source: `ClassInheritorsSearch`,
`OverridingMethodsSearch`, `DefinitionsScopedSearch`, `JavaPsiFacade` (all under
`github.com/JetBrains/intellij-community`). JetBrains MCP equivalent: none for
either tool.
