# `psi.type_hierarchy` + `psi.goto_implementation`

## Purpose & motivation

Two related PSI navigation tools filling gaps in `psi.*` AND in JetBrains' MCP
server (which has neither). `psi.type_hierarchy` mirrors IntelliJ's Hierarchy
tool window (Ctrl+H) — given a class, return its supertype / subtype tree.
`psi.goto_implementation` mirrors Ctrl+Alt+B — given a position on an
interface, abstract class, or method, return concrete implementors / overrides.

Today `psi.find_usages` with `includeImplementations=true` partially overlaps
goto-impl but lumps overrides into a usages list, has no FQN entry point, and
returns no multi-level tree.

**Success criterion**: an agent answers (1) "supertype chain of
`com.intellij.openapi.editor.Editor`?" and (2) "concrete impls of
`FileEditorProvider`?" in one MCP call each.

## Tool specification

### `psi.type_hierarchy`

```kotlin
@McpTool(name = "psi.type_hierarchy")
@McpDescription("""…verbatim below…""")
suspend fun psi_type_hierarchy(
    @McpDescription("FQN. Mutually exclusive with file+offset; takes precedence when both supplied.")
    target: String? = null,
    @McpDescription("VFS URL. null → active editor tab. Used when `target` is null.") fileUrl: String? = null,
    @McpDescription("Document offset on a class decl/ref. Alternative to line+column.") offset: Int? = null,
    @McpDescription("1-based line.") line: Int? = null,
    @McpDescription("1-based column.") column: Int? = null,
    @McpDescription("\"up\" / \"down\" / \"both\" (default).") direction: String = "both",
    @McpDescription("\"file\" / \"project\" (default) / \"all\" (slow).") scope: String = "project",
    @McpDescription("Max tree depth from target. Default 5.") maxDepth: Int = 5,
    @McpDescription("Hard cap on total nodes across supertypes + subtypes. Default 200.") maxNodes: Int = 200,
): TypeHierarchyResponse
```

`@McpDescription` (verbatim — trim-margin, the reflection bridge strips margins):

```
|Returns the type hierarchy of a class — supertypes (parents) and/or subtypes
|(implementors / extenders) — as a tree rooted at the target. Mirrors the
|IntelliJ Hierarchy tool window (Ctrl+H, "Type Hierarchy").
|
|Use this when:
|  - The agent needs what a class extends / implements ("up").
|  - The agent needs who extends or implements a class ("down").
|  - A multi-level tree is more useful than a flat list.
|  - Sealed-type exhaustiveness check — every direct subtype is included and
|    the response flags `isSealed`.
|
|Do NOT use this when:
|  - You only need concrete implementations of an interface / abstract member —
|    psi.goto_implementation is more focused and returns method signatures.
|  - You want references / call sites — that is psi.find_usages.
|
|Target resolution: pass `target` (FQN, takes precedence) OR a position
|(fileUrl + offset OR line+column) on a class declaration / reference.
|Anonymous and local classes are recognised at a position but never surface as
|subtype nodes (no FQN).
|
|Scope (default "project"):
|  - "file"    — subtype walk restricted to one file (rarely useful).
|  - "project" — project sources only. Standard default.
|  - "all"     — includes library sources. For java.util.List or marker types
|                like java.lang.Object the subtype walk can saturate the 10s
|                read-action timeout. A warning is appended; prefer "project".
|
|Caps: maxDepth (5) and maxNodes (200) bound the walk. On a cap, response's
|`truncated` is set and the cut branch's leaf carries `childrenTruncated=true`.
|
|Returns: { target: HierarchyClassRef, supertypes: HierarchyNode?, subtypes:
|HierarchyNode?, direction, scope, truncated, warnings[] }. Each HierarchyNode
|has `node` + `children[]` (parents for supertypes, child classes for subtypes).
|java.lang.Object is included as the supertype root when walking "up" but its
|subtype walk is always rejected (would be the world).
|
|Examples:
|  target="com.intellij.openapi.editor.Editor"         — both directions, project
|  target="java.util.List", direction="up"             — super-interfaces only
|  fileUrl=null, line=42, column=14, direction="down"  — subtypes of class under caret
|  target="com.acme.Sealed", direction="down"          — exhaustive sealed list
```

### `psi.goto_implementation`

```kotlin
@McpTool(name = "psi.goto_implementation")
@McpDescription("""…verbatim below…""")
suspend fun psi_goto_implementation(
    @McpDescription("VFS URL. null → active editor tab.") fileUrl: String? = null,
    @McpDescription("Document offset on a method, interface, or abstract class.") offset: Int? = null,
    @McpDescription("1-based line.") line: Int? = null,
    @McpDescription("1-based column.") column: Int? = null,
    @McpDescription("\"file\" / \"project\" (default) / \"all\" (slow).") scope: String = "project",
    @McpDescription("Hard cap on returned implementations. Default 200.") maxResults: Int = 200,
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

`model/PsiInfo.kt` (append):

```kotlin
@Serializable data class HierarchyClassRef(
    val fqn: String?, val psiClass: String,
    val fileUrl: String? = null, val declarationRange: TextRangeInfo? = null,
    val isInterface: Boolean = false, val isAbstract: Boolean = false,
    val isFinal: Boolean = false, val isSealed: Boolean = false,
    val modifiers: List<String> = emptyList(),
)
@Serializable data class HierarchyNode(
    val node: HierarchyClassRef,
    val children: List<HierarchyNode> = emptyList(),
    val childrenTruncated: Boolean = false,
)
@Serializable data class TypeHierarchyResponse(
    val target: HierarchyClassRef,
    val supertypes: HierarchyNode? = null, val subtypes: HierarchyNode? = null,
    val direction: String, val scope: String,
    val truncated: Boolean = false, val warnings: List<String> = emptyList(),
)
@Serializable data class ImplementationTarget(
    val name: String?, val psiClass: String, val kind: String,  // "class" | "method"
    val fileUrl: String? = null, val declarationRange: TextRangeInfo? = null,
    val isAbstract: Boolean = false, val isInterface: Boolean = false,
)
@Serializable data class ImplementationInfo(
    val fileUrl: String, val range: TextRangeInfo, val lineSnippet: String,
    val declaringClassFqn: String? = null, val signature: String? = null,
    val isAbstract: Boolean = false, val isOverride: Boolean = false,
)
@Serializable data class GotoImplementationResponse(
    val target: ImplementationTarget, val implementations: List<ImplementationInfo>,
    val scope: String, val total: Int,
    val truncated: Boolean = false, val warnings: List<String> = emptyList(),
)
```

Validation: `direction` ∈ {up,down,both}; `scope` ∈ {file,project,all};
`maxDepth` 1..20; `maxNodes` 1..5_000; `maxResults` 1..5_000. Invalid →
`McpExpectedError`.

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
exposed to all of the above via the platform's light-class adapters
(`KtLightClass`, `KtLightMethod`) — no Kotlin-plugin link-time dep needed.
Sealed detection: simple-name probe on `"KtClass"` checking `SEALED_KEYWORD`
plus `PsiClass.hasModifierProperty("sealed")` for Java 17+ sealed — same
no-link-time-dep pattern as `PsiUsageSearcher.isLocalVariableLike`.

Position resolution reuses `PsiToolset.resolveFile` / `resolveOffset` and
`PsiUsageSearcher.resolveTarget`'s follow-reference-or-named-ancestor walk,
restricted to `PsiClass` (type_hierarchy + class-mode goto_impl) or
`PsiMethod` (method-mode goto_impl).

## Threading

No EDT. Both run inside `readActionBlocking { … }` and wrap each search in
`DumbService.computeWithAlternativeResolveEnabled<R, RuntimeException> { … }` —
same as `psi_find_usages`. All `*Search` APIs respect the read-action context
and check PCE per result, so the 10 s readAction cap propagates cleanly. No
caching (state-dependent).

## Timeout strategy

Hard 10 s cap (CLAUDE.md). Risky surface: `ClassInheritorsSearch` /
`DefinitionsScopedSearch` / `OverridingMethodsSearch` on a hot interface in
`scope="all"` (e.g. `List`, `Object`, `Object.toString()`) can return thousands
of hits and saturate the budget.

Mitigations baked in:
- **Default `scope="project"`** (matches IntelliJ Hierarchy default).
- **Hard caps**: `maxNodes=200` / `maxResults=200`; trip `truncated=true`.
- **`scope="all"` warning** appended even on success, so the agent learns to
  narrow on follow-ups.
- **`java.lang.Object` special-case** for type_hierarchy: subtype walk
  rejected with warning.
- **Per-result PCE check** in each `Query.forEach(Processor { … })` — same as
  `PsiUsageSearcher`. Stop the moment a cap trips.

If a caller genuinely needs more than 10 s, the answer is narrower scope or
paging — not a higher timeout.

## Edge cases

1. **`target` FQN does not resolve** → `McpExpectedError("No class found for FQN: …")`.
2. **Position not on a class or method** → `McpExpectedError("Caret is not on a class/method declaration or reference")`.
3. **Target is `final` class** — empty subtypes, single-node tree, no warning.
4. **Target is `java.lang.Object`** — subtype walk rejected with warning;
   supertype walk returns the trivial root.
5. **Anonymous / local classes** — recognised at a position (`fqn=null`),
   excluded from subtype-walk results.
6. **Kotlin objects (singletons)** — `KtLightClass` final, no subtypes — handled
   like any other final class.
7. **Sealed class / sealed interface** — detected via Kotlin `SEALED_KEYWORD`
   on `KtClass` (simple-name probe) OR `PsiClass.hasModifierProperty("sealed")`.
   Set `isSealed=true` + warning ("sealed type — direct children are
   exhaustive"). Children still come from `ClassInheritorsSearch`.
8. **Method on a generic interface** — overrides surface with overrider's
   erasure signature; we do NOT unify generics across interface/impl.
9. **Final method** — empty overrides, not an error.
10. **Project in dumb mode** — `computeWithAlternativeResolveEnabled` handles
    most; surviving `IndexNotReadyException` caught and surfaced as warning.
11. **Multi-resolve under caret** — follow first non-null target (matches
    `PsiUsageSearcher.resolveTarget`); append warning with skipped count.
12. **Library-only target** — `target.fileUrl` is `jar://…`; `scope="project"`
    finds only project subclasses.

## Files to create/modify

| Path | Op | What |
|------|----|------|
| `core/PsiHierarchyResolver.kt` | Create | Headless logic for both tools: FQN/position → PsiClass/PsiMethod, super/sub walk, override search, result shaping. |
| `model/PsiInfo.kt` | Edit | Append the six new `@Serializable` types above. |
| `model/args/PsiArgs.kt` | Edit | Append `TypeHierarchyArgs`, `GotoImplementationArgs`. |
| `tools/PsiToolset.kt` | Edit | Add `psi_type_hierarchy` and `psi_goto_implementation` (thin wrappers). |
| `src/test/kotlin/.../core/PsiHierarchyResolverTest.kt` | Create | Unit: arg validation + truncation flags + Object special-case. |
| `src/test/kotlin/.../core/platform/PsiHierarchyResolverPlatformTest.kt` | Create | Platform tests with Java + Kotlin fixtures. |

No new XML wiring: both methods live on `PsiToolset` (already in
`mcp-integration.xml`). `JavaPsiFacade` requires `com.intellij.modules.java`;
in IDEs without it, surface a clean `McpExpectedError` rather than CNFE. If CI
shows real breakage, split to a new `PsiHierarchyToolset` under
`java-introspect.xml`.

## Test plan

**Unit** — pure JVM:
- accepts `direction`/`scope` only in valid sets; rejects others.
- validates `maxDepth` / `maxNodes` / `maxResults` bounds.
- truncation flags on supertype + subtype caps.
- `Object` FQN target → subtype rejected + warning.
- position requires `offset` OR `(line,column)`; neither → error.
- `target` FQN takes precedence over position args.
- signature normaliser produces `"R name(P p)"`.

**Platform** — `BasePlatformTestCase`, fixtures under `src/test/testData/psi/hierarchy/`:
`Animal.java` (abstract) ← `Dog.java`, `Cat.java` ← `Puppy.java`;
`Greeter.java` (interface) ← `EnglishGreeter`, `FrenchGreeter`;
`SealedShape.kt` (sealed) ← `Circle`, `Square`; `KtAnimal.kt` (Kotlin mirror).

- `typeHierarchyByFqn_Animal_returnsExtenders`.
- `typeHierarchyByPosition_caretOnDog_returnsAnimalSupertype`.
- `typeHierarchyBothDirections_Dog_returnsParentsAndChildren`.
- `typeHierarchyMaxDepth1_truncatesDeepChildren` (sets `childrenTruncated`).
- `typeHierarchyMaxNodes2_truncates` (sets `truncated=true`).
- `typeHierarchySealedClass_flagsExhaustive` (`isSealed=true` + warning).
- `typeHierarchyKotlinAbstract_returnsExtenders` (via KtLightClass).
- `typeHierarchyObject_subtypeRejected`.
- `typeHierarchyFinalClass_returnsEmptySubtypes`.
- `gotoImplementationOnInterface_Greeter_returnsTwoImpls` (kind="class").
- `gotoImplementationOnAbstractMethod_returnsConcreteOverrides` (kind="method").
- `gotoImplementationOnFinalMethod_returnsEmpty`.
- `gotoImplementationScopeAll_appendsWarning`.

## Estimated effort

| Step | Hours |
|------|-------|
| Args + response models | 0.5 |
| `PsiHierarchyResolver` supertype + subtype walk + caps | 3 |
| `PsiHierarchyResolver` goto-impl (class + method) + signature norm | 2 |
| Toolset methods + `@McpDescription` | 1 |
| Unit tests | 1.5 |
| Java + Kotlin fixtures + platform tests | 3 |
| Doc-gen verification + `runIde` smoke (Hierarchy parity) | 1 |
| **Total** | **~1.5 days (12 h)** |

## Open questions / risks

1. **goto_implementation on a class — follow `extends` AND `implements`?**
   `DefinitionsScopedSearch` natively does both. **Default: yes**, both
   returned; `declaringClassFqn` lets callers filter.
2. **Walk Kotlin sealed children automatically?** Sealed children fall out of
   `ClassInheritorsSearch` naturally. **Proposed**: no special walk; just flag
   `isSealed=true` + warning so the agent knows the list is exhaustive.
3. **Deduplicate impls overridden further down?** `OverridingMethodsSearch(checkDeep=true)`
   returns BOTH abstract mid-tier and concrete leaves. The "what concretely
   implements X?" question wants concrete only. **Proposed: filter
   `!isAbstract` for methods by default**; add `includeAbstract` flag only if
   a real caller needs both. Not in v1 signature.
4. **`OverridingMethodsSearch` order** — platform makes no stability promise;
   sort by `(fileUrl, range.startOffset)` so tests can assert order.
5. **Java module dep** — `JavaPsiFacade` ClassNotFound on IDEs without
   `com.intellij.modules.java` → degrade with clean error; split if CI insists.

## References

- Existing: `tools/PsiToolset.kt#psi_find_usages` (positional + scope +
  truncation pattern); `core/PsiUsageSearcher.kt` (`DefinitionsScopedSearch` +
  `resolveTarget`); `core/PsiModifiers.kt`.
- IntelliJ source:
  - ClassInheritorsSearch: https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/psi/search/searches/ClassInheritorsSearch.java
  - OverridingMethodsSearch: https://github.com/JetBrains/intellij-community/blob/master/java/java-indexing-api/src/com/intellij/psi/search/searches/OverridingMethodsSearch.java
  - DefinitionsScopedSearch: https://github.com/JetBrains/intellij-community/blob/master/platform/indexing-api/src/com/intellij/psi/search/searches/DefinitionsScopedSearch.java
  - JavaPsiFacade: https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/JavaPsiFacade.java
- JetBrains MCP equivalent: **none** for either tool.
