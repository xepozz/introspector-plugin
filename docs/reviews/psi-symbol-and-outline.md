# Review: psi.symbol_at + psi.get_outline

Branch: `claude/project-features-analysis-odEwP` @ `b837ba5` (salvage) +
`733906d` (green-fix).
Reviewer scope: `core/PsiSymbolResolver.kt`, `core/PsiOutlineCollector.kt`,
`core/PsiKindClassifier.kt`, the appended models in `model/PsiInfo.kt`, the
two new `@McpTool` methods on `tools/PsiToolset.kt`, and any tests the plan
asked for.

## Verdict
Needs changes before merge. The shape is right — `StructureViewBuilder` is
used per the plan, `readActionBlocking` + `computeWithAlternativeResolveEnabled`
mirror the rest of `psi.*`, the kind taxonomy is broad enough, and the
description prose is on-spec — but `includeInherited` is wired in but
demonstrably a no-op (the comment block at `PsiOutlineCollector.kt:160-166`
admits this), the entire test suite the plan demanded is missing (zero unit
+ zero platform), `psi.symbol_at`'s reference path is NOT injection-aware
for the "caret on a SQL identifier inside a Kotlin string" case the plan
explicitly calls out, and there's roughly a dozen lines of dead /
defensive code that the spec didn't ask for. Fix the inherited toggle, add
the test fixtures, and route injection lookup through
`InjectedLanguageManager` BEFORE `findReferenceAt` and this is mergeable.

## Summary
- Three new core files (`PsiSymbolResolver` 190 LOC, `PsiOutlineCollector`
  212 LOC, `PsiKindClassifier` 365 LOC), one model append (`PsiInfo.kt:222-307`),
  two new `@McpTool` methods on `PsiToolset` (`psi_symbol_at` @425,
  `psi_get_outline` @616). No new `args/` types — params are inline. No new
  META-INF wiring (correct — `psi.*` already registered).
- Doc-gen — `@McpDescription` strings follow the 5-section convention
  verbatim from the plan; per-parameter descriptions are present on every
  arg of both methods.
- Hard rules: 10 s read-action cap inherited; no new `withTimeoutOrNull` /
  latch / future. Serialization classloader policy untouched. Kotlin
  classifier dispatches by simple-name + reflection (no static `Kt*` imports)
  — matches the `ExecToolset` precedent for optional-module access. OK.
- `PositionResolver` is reused via `PsiToolset.resolveOffset` shim
  (`PsiToolset.kt:811-812`) — both new tools get bounds-checking for free.
- Both tools wrap in `DumbService.computeWithAlternativeResolveEnabled` —
  `symbol_at` because it resolves references, `get_outline` because the
  plan said it didn't need to but the implementation adds it anyway
  (harmless, slightly conservative).
- `model.dispose()` is called in a `finally` block
  (`PsiOutlineCollector.kt:128-130`) — good, matches the JetBrains
  guidance that StructureViewModel implementations may register listeners.

## Findings (numbered, severity-tagged)

### 1. [HIGH] `psi.symbol_at` is NOT injection-aware on the REFERENCE path — `findReferenceAt` runs against the host file only

The plan, edge case #3:
> Injected fragments — `findInjectedElementAt` first; injected ancestors
> win (SQL identifier reported, not host `KtLiteralStringTemplate`).

What the code does (`PsiSymbolResolver.kt:104-134`):
```kotlin
val ref: PsiReference? = psiFile.findReferenceAt(offset)  // <-- HOST file
if (ref != null) { ...; if (resolved != null) return resolved to true }

// Only here, on the declaration fallback, does injection lookup happen
val leaf = InjectedLanguageManager.getInstance(project)
    .findInjectedElementAt(psiFile, offset)
    ?: psiFile.findElementAt(offset)
    ?: return null
val named = PsiTreeUtil.getNonStrictParentOfType(leaf, PsiNamedElement::class.java)
```

`PsiFile.findReferenceAt(offset)` does NOT recurse into injected fragments —
it walks the HOST's PSI tree only. The plan's edge case is "caret on a SQL
table reference inside a Kotlin string"; the SQL reference will resolve via
the injected file's `findReferenceAt`, but we never call it. We instead
either:
- find a host reference (the `KtStringTemplate` itself — unlikely to carry
  one), and return the host symbol, OR
- fall through to the injected element lookup, then walk up to the nearest
  `PsiNamedElement` — but skipping any reference resolution on the injected
  side. The "Ctrl-click to the resolved column declaration" behaviour the
  plan promises will not happen.

Fix: check the injected fragment first; if there's an injected leaf, try
`injectedFile.findReferenceAt(offsetInInjected)` before the host path.
Pattern is documented at
`InjectedLanguageManager.getInjectionHost` + `getInjectedPsiFiles`.
Note that `PsiUsageSearcher.resolveTarget` has the same bug (cited in the
plan as the established pattern!) — `psi_find_usages` is broken in the
same way today. Fixing it here and back-porting to `PsiUsageSearcher` would
be a unified hierarchy-resolution improvement, but at minimum
`psi_symbol_at` needs to honour what its `@McpDescription` and the plan's
edge case #3 promise.

### 2. [HIGH] `includeInherited=true` is a no-op — outright admitted in the source comment

`PsiOutlineCollector.kt:160-166`:
```kotlin
// includeInherited=false drops anything the structure view marked as inherited. We
// use a duck-typed check (`StructureViewTreeElement` doesn't itself expose an
// "isInherited" flag, but many implementations subclass JavaInheritedMembersNodeProvider
// contributions that ultimately make inherited members appear when groupers/filters
// are enabled — the v1 contract simply doesn't surface inherited unless explicitly
// requested, so we err on the side of skipping nothing extra here for now).
// Note: getGroupers() would let us surface inherited explicitly; deferred to v2.
```

This is the entire implementation of `includeInherited`. The
`StructureViewModel.getNodeProviders()` (added since 2017) is what surfaces
inherited members — JavaStructureViewBuilderFactory's model contributes
`InheritedMembersNodeProvider` which the caller must opt into via
`model.isEnabled(provider)` checks. The current walker ignores
`getNodeProviders()` entirely, so:
- `includeInherited=false` (default): correct by accident — inherited
  members aren't shown because we never enable the provider.
- `includeInherited=true`: silently equivalent to `false`. Agents asking
  for "the methods this class inherits from its superclass" get nothing,
  with no warning that the toggle was a lie.

Plan's test `testIncludeInheritedTrueAddsSuperclassMethods` would have
caught this on day 1 — it doesn't exist (Finding 4 below).

Fix options: (a) remove the parameter entirely and document that inherited
members are out-of-scope for v1, (b) actually implement it by iterating
`model.nodeProviders` and folding their children into each class node's
children list. (a) is faithful; (b) is what the description claims.

### 3. [MED] `psi.symbol_at` defensive offset-bounds branch is dead code on the toolset path

`PsiSymbolResolver.kt:50-57`:
```kotlin
val docTextLen = hostDocument?.textLength ?: psiFile.textLength
if (offset > docTextLen) {
    warnings += "position past end of file"
    return SymbolAtResponse(...)
}
```

By the time we reach `resolveAt`, `PsiToolset.psi_symbol_at` has called
`PositionResolver.resolveOffset(document, offset, line, column)` (line 494),
which already runs `require(offset in 0..document.textLength)` and throws
`IllegalArgumentException` on out-of-bounds. So the warning branch can
never fire from MCP traffic — it's only reachable if a future caller
bypasses `PositionResolver`. The plan's edge case #1 says
"`resolveOffset` already clamps; `findElementAt` returns null;
`symbol=null` + warning" — but `resolveOffset` doesn't clamp, it throws,
so the contract drifts here.

Either (a) clamp in `PositionResolver` (turning the `require` into
`coerceIn`) and let the warning branch in `PsiSymbolResolver` fire as
planned, or (b) drop the dead branch from `PsiSymbolResolver` and document
that offset past EOF is rejected before reaching the resolver. (a) matches
the spec; (b) matches the current behaviour. Pick one and align both files.

### 4. [HIGH] Zero tests — the plan demanded three test files, none exist

The plan lists:
- `src/test/kotlin/.../core/PsiKindClassifierTest.kt` (unit — mocked PSI)
- `src/test/kotlin/.../core/platform/PsiSymbolResolverPlatformTest.kt`
  (Java + Kotlin fixtures: 7 named cases)
- `src/test/kotlin/.../core/platform/PsiOutlineCollectorPlatformTest.kt`
  (6 named cases including the `testIncludeInheritedTrueAddsSuperclassMethods`
  that would have caught Finding 2)

`find src/test -name "*Symbol*" -o -name "*Outline*" -o -name "*KindClassifier*"`
returns nothing. The salvage commit `b837ba5` was tagged "(untested)" and
`733906d` ("make salvaged features compile/test green") added no test
coverage for this feature — it only made the existing suite stop failing.

Without `PsiKindClassifierTest`, the Kotlin classifier (which dispatches on
simple-name strings — fragile to platform renames) has zero CI coverage.
Without the platform tests, edge cases #1–#10 from the plan (whitespace,
EOF, injections, polyvariant, anonymous classes, …) are entirely
untested. The plan budgeted 2.5 hours for them; that work was skipped.

### 5. [MED] Kotlin modifier extraction regex-tokenises `KtModifierList.getText()`

`PsiKindClassifier.kt:338-351` splits the raw modifier-list text on
whitespace and filters by a known keyword set. Vulnerable to multi-word
annotation args (`@field:[Foo("bar") Baz]` produces broken tokens) but
the annotation noise is mostly discarded by the keyword filter. The clean
path is `KtModifierList.hasModifier(KtModifierKeywordToken)` (reflective
dispatch — the plugin already does it everywhere else). Not blocking; the
existing comment block at `:332-337` should call out the false-positive
risk explicitly rather than "good enough".

### 6. [LOW] `enclosingDeclarationName` returns just a name — no kind / chain

`PsiSymbolResolver.kt:171-181` walks parents and returns the first
non-blank name. For a local inside an anonymous class inside a method,
the anonymous (no name) is skipped silently — agent gets only the outer
method name, losing the anonymous-class context. Spec-compliant (the
field is `containingDeclarationName: String?`) but
`"OuterClass.method.<anonymous>"` would be more useful. Defer.

### 7. [LOW] `PsiOutlineCollector.walk` has dead-code suppression

`PsiOutlineCollector.kt:197-198`:
```kotlin
// Suppress no-op note about ownIndex — keeps the linter quiet without altering logic.
@Suppress("UNUSED_VARIABLE") val unusedOwnIndex = ownIndex
```

`val ownIndex = ctx.emitted` is computed at line 177 and used only by this
suppression — drop the suppression and the variable. The pattern smells
like an aborted "use ownIndex to compute relative subtree size" feature.
1-line cleanup; no behaviour change.

### 8. [LOW] `PsiOutlineCollector`'s `TreeBasedStructureViewBuilder` cast rejects valid models

`PsiOutlineCollector.kt:70-82`:
```kotlin
if (builder !is TreeBasedStructureViewBuilder) {
    return GetOutlineResponse(..., warnings = listOf(
        "StructureViewBuilder for fileType=$fileType is not tree-based ..."
    ))
}
```

`TreeBasedStructureViewBuilder` is the common base, but
`PlainTextStructureViewBuilder` (used for .txt / fallback) is also tree-based
and returns a one-node model with the file root. More importantly, some
languages contribute a `CustomStructureViewBuilder` for highlighter-driven
outlines — those still expose `createStructureViewModel()` via the parent
`StructureViewBuilder` interface. Today the cast just bails. The plan's
"v1: only TreeBasedStructureViewBuilder" decision is defensible, but the
warning message should suggest `psi.get_structure` as the fallback so the
agent doesn't dead-end. 1-line message tweak.

### 9. [LOW] `PsiOutlineCollector` does NOT short-circuit when `psiFile.fileType.isBinary`

`PsiOutlineCollector.kt:54` calls
`getStructureViewBuilder(psiFile)` immediately; if the file is binary
(`.bin`, `.class`, `.png`), the builder is usually null (correct response
path) but for `.class` files the bytecode-decompiler structure view DOES
contribute a builder, which then walks decompiled members. Probably fine —
that's the same shape an agent wants — but the plan's edge case #4 says
"binary file → empty + warning". The current code gets there via the
null-builder branch, which doesn't actually mention "binary". 1-line
add to the warning to distinguish binary from "no contributor".

### 10. [LOW] `@McpDescription` for `psi.get_outline` promises "includeInherited fold in superclass members" — false until Finding 2 is fixed

`PsiToolset.kt:651`:
```kotlin
|  includeInherited=true                     — fold in superclass members
```

Description-vs-implementation drift; the agent will trust the description
and request `includeInherited=true` and get the same response as
`false`. Either tone down to "(currently no-op; reserved for v2)" or
implement it (Finding 2). The current state is worst-of-both.

## Threading & EDT model — OK

- Both tools wrap `readActionBlocking { … }` at `PsiToolset.kt:492` /
  `:670`. `StructureViewModel.root.children` walks PSI structure under a
  read action — safe.
- `PsiOutlineCollector.collect` runs `model.dispose()` in `finally`
  (`PsiOutlineCollector.kt:128-130`) — matches the
  `Disposer.register(view, model)` pattern from
  `TreeBasedStructureViewBuilder.createStructureView`. Good.
- `PsiSymbolResolver` does NOT touch the editor / EDT directly — host
  document is passed in. No EDT bouncing required. Good.
- `createStructureViewModel(null)` with a null editor is documented as
  allowed (`@Nullable` annotation in the abstract base); we pass null
  because no editor exists for arbitrary fileUrl callers. Confirmed OK on
  the IntelliJ side.

## Timeouts — OK

10 s read-action cap inherited from `readActionBlocking`. Neither tool
adds a `withTimeoutOrNull` / latch. `symbol_at` is plan-quoted at <50 ms;
`get_outline` <200 ms even on a 10k-line file with 500 methods bounded by
`maxNodes`. Hard rules ok.

## @McpDescription quality — mostly OK

Both descriptions follow the 5-section convention verbatim from the plan:
"what it does → use when → don't use when → returns → examples". The kind
taxonomy is enumerated inline. The injection / polyvariant edge cases are
called out. Two drift items:
- `psi.get_outline` lies about `includeInherited` (Finding 10).
- `psi.symbol_at` claims `docText` is markdown-aware ("KDoc / JavaDoc
  snippet, truncated") but it's raw text — the description says "raw text
  — markdown is not rendered" so this is actually correct after the recent
  edit; good.

## Test coverage — MISSING

Zero unit tests, zero platform tests. See Finding 4. The salvage commit was
tagged "(untested)" and never grew tests. CI on this PR is green only
because nothing exercises the new code paths.

## Recommended actions before merge

1. **Finding 1** — route `psi_symbol_at`'s reference path through the
   injected file (`InjectedLanguageManager.findInjectedElementAt` →
   `injectedFile.findReferenceAt(offsetInInjected)`) before the host
   path. Plan edge case #3 mandates this.
2. **Finding 2** — either implement `includeInherited` via
   `StructureViewModel.getNodeProviders()` + per-provider enable, or
   remove the parameter and update the description. Don't ship a no-op.
3. **Finding 4** — add the three test files the plan listed. The
   classifier unit suite is cheap (pure JVM, ~30 min); the platform
   suites use the same `BasePlatformTestCase` pattern as
   `PsiUsageSearcherPlatformTest.kt`.
4. **Finding 3** — pick clamp-vs-throw for past-EOF offsets and align
   `PositionResolver` + `PsiSymbolResolver`.
5. Findings 5–10 are nits; clean up incidentally.

## File / line references

- `core/PsiSymbolResolver.kt:111` — host-only `findReferenceAt` (Finding 1)
- `core/PsiSymbolResolver.kt:50-57` — dead bounds branch (Finding 3)
- `core/PsiSymbolResolver.kt:128` — injection lookup on declaration path only
- `core/PsiOutlineCollector.kt:160-166` — `includeInherited` no-op
  (Finding 2)
- `core/PsiOutlineCollector.kt:70-82` — TreeBased-only filter (Finding 8)
- `core/PsiOutlineCollector.kt:197-198` — dead `@Suppress` (Finding 7)
- `core/PsiKindClassifier.kt:338-351` — regex modifier extraction (Finding 5)
- `tools/PsiToolset.kt:651` — `includeInherited` lie in `@McpDescription`
  (Finding 10)
- `tools/PsiToolset.kt:492-509` — `psi_symbol_at` wiring (OK)
- `tools/PsiToolset.kt:665-681` — `psi_get_outline` wiring (OK)
- `model/PsiInfo.kt:222-307` — appended models (OK)
- `util/PositionResolver.kt:24` — `require` rather than `coerceIn`
  (Finding 3)

Sources consulted:
- Plan `docs/plans/psi-symbol-and-outline.md`
- [Structure View Factory — IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/structure-view-factory.html)
- [TreeBasedStructureViewBuilder source on GitHub](https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/ide/structureView/TreeBasedStructureViewBuilder.java)
- [Coroutine Read Actions — IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html)
