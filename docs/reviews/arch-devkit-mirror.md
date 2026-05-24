# Review: arch.check_lock_requirements + arch.check_threading_requirements

Branch: `claude/project-features-analysis-odEwP` @ `c2d810d`
Scope: `core/RequirementsAnalyzer.kt`, `model/RequirementsInfo.kt`,
`model/args/ArchArgs.kt` (two new arg classes), the two new
`@McpTool` methods on `tools/ArchitectureToolset.kt`, the
`util/PositionResolver.kt` extraction, and the 50-line addition to
`core/PsiUsageSearcher.kt`. Plan: `docs/plans/arch-devkit-mirror.md`.

## Verdict
**Needs changes.** The skeleton is in good shape — annotation FQNs are
hard-coded as the plan demanded, read-action wrapping is correct, the
`@McpDescription` mirror is verbatim, and the `PositionResolver` extraction
is a byte-for-byte refactor that preserves every existing `psi.*` semantic.
But the analyzer ships **zero tests, zero fixtures**, an unused 50-LOC
public API (`PsiUsageSearcher.callersOf` — never called), a duplicated
overrides-search loop, and one decision-tree path
(`@RequiresReadLockAbsence` + caller lexically inside `ReadAction`) that the
plan explicitly carves out but the implementation misclassifies. Without
the platform test the analyzer's status decisions are unverified guesses.

## Findings (numbered, severity-tagged)

### Correctness

1. **[HIGH] `RequirementsAnalyzer.kt:521-530` — `@RequiresReadLockAbsence`
   violation by a `ReadAction` wrapper goes undetected when the caller's
   own annotations are empty.** The plan (edge case 10) and the
   implementation's own kdoc both promise: "caller in `runReadAction
   { target() }` → `mismatch`". The code checks `wrapperHints.any {
   isReadOrWriteActionWrapper(it) }`, but `detectEnclosingWrappers` only
   collects wrappers whose simple name is in the **target-kind's** set —
   for `wrapperKind = LOCK` that includes `runReadAction`, so this path
   does work for the lock tool. *However*, the wrappers detected are
   filtered ONLY against `LOCK_WRAPPER_SIMPLE` (line 449-450). For
   threading callers the same `runReadAction` envelope is invisible to
   the analyzer — fine, threading doesn't care. The bug is elsewhere:
   the violation check `isReadOrWriteActionWrapper(it)` returns true
   only for `inside-ReadAction…`, `inside-WriteAction…`,
   `inside-runReadAction`, `inside-runWriteAction` — but
   `LOCK_WRAPPER_SIMPLE` *also* contains `nonBlocking`, `compute`,
   `runReadAction`, and the helper added `computeWithAlternativeResolveEnabled`.
   A caller using `ReadAction.nonBlocking { tgt() }` therefore yields
   hint `inside-nonBlocking` which `isReadOrWriteActionWrapper` does not
   recognise → reported `ok` when it should be `mismatch` (for
   `@RequiresReadLockAbsence`) or remain `ok` (for `@RequiresReadLock`).
   Decide what `nonBlocking` means here, then make the recognised-wrapper
   tagging share one source of truth (currently three: `LOCK_WRAPPERS`,
   `LOCK_WRAPPER_SIMPLE`, and the `isReadOrWriteActionWrapper`
   string-match predicate). The same drift exists for
   `runProcessWithProgressAsynchronously` vs. `isBgtWrapper`.

2. **[HIGH] `RequirementsAnalyzer.kt:207-241` — `includeImplementations`
   re-runs the same `DefinitionsScopedSearch + ReferencesSearch` work the
   plan's note about `PsiUsageSearcher.callersOf` was supposed to
   centralise.** `PsiUsageSearcher.callersOf` was added (PsiUsageSearcher.kt:304-344)
   with this exact body — but the analyzer never calls it. Either delete
   `callersOf` (50 LOC of dead public API) or have `analyze()` route
   through it. The duplicate inline loop also conflicts subtly with the
   primary `ReferencesSearch` pass above: when
   `includeImplementations=true` a single override that's itself
   discovered as a reference can be analysed twice — the
   `(fileUrl, startOffset)` dedupe key catches identical hits but loses
   the secondary analysis (different caller annotations may end up on
   the dropped copy if a derived class adds annotations).

3. **[HIGH] `RequirementsAnalyzer.kt:172-180` — Empty-`expected`
   short-circuit kills `expected.isEmpty()` branch inside `decide()`.**
   The early return rightly produces a "no contract" response when the
   target has no relevant annotation, but the plan also wants a `reason:
   "no contract"` to surface on the *response* (it only sets `expected:
   []` + `callSites: []`, no reason field exists at the response level).
   Either add `targetReason` to `CheckRequirementsResponse` so the agent
   can distinguish "the method has no contract" from "we found zero call
   sites" (both currently look identical), or remove the now-unreachable
   `if (expected.isEmpty()) Verdict("ok", "no contract", emptyList())`
   branch at lines 509-511 (dead code; reads like a bug).

4. **[MEDIUM] `RequirementsAnalyzer.kt:281-283` — Positional resolution
   only finds `PsiMethod`, never the Kotlin `KtNamedFunction`.** The
   plan's tooltips advertise "method at row 42 in active editor", and
   light classes mean Kotlin functions DO surface as `PsiMethod` via
   `KtLightMethod` *if* the file is in a content root and the Kotlin
   plugin produced light classes. But for top-level Kotlin functions
   without a `@JvmName` annotation, or fixtures in unindexed
   directories, `getParentOfType(PsiMethod)` walks the host file and
   returns null. Add a `nearestKotlinExecutable`-style sibling for the
   positional path (or use `UMethod` via `UastUtils` which uniformly
   adapts both PSI shapes — depends on `com.intellij.modules.lang` only,
   already a base IDE module).

5. **[MEDIUM] `RequirementsAnalyzer.kt:574-579` — `@RequiresBlockingContext`
   never reaches `unknown`.** The plan open Q #3 explicitly defers
   coroutine-context detection but the implementation still emits hard
   `mismatch` rather than `unknown` for plain callers. Inverting to
   `unknown` (the agent escalates rather than reports a false positive
   for callers inside `suspend fun` where we can't reason) matches the
   spirit of the rest of the table. At minimum: detect "caller is a
   Kotlin `suspend fun`" by simple-name on the modifier-list owner and
   downgrade to `unknown`.

6. **[MEDIUM] `RequirementsAnalyzer.kt:606-642` — `isInsideOpaqueDispatcher`
   walks up TWICE per call site (once via `detectEnclosingWrappers`,
   once here), with subtly different stopping rules.** The
   "opaque dispatcher" detector ascends until it finds a lambda inside
   a call expression whose simple name is NOT in any wrapper set.
   Problem: it returns true on the *first* lambda+call pair it sees,
   even if the call is in fact a recognised wrapper several frames
   further up — e.g. `runReadAction { something({ tgt() }) }` would
   classify the inner `tgt()` as opaque because the *immediate* lambda
   is wrapped by an arbitrary `something(...)`. The analyzer would then
   downgrade an otherwise-`ok` site to `unknown`. Fold the two walks
   into one pass that collects ALL ancestor calls and decides at the
   end.

7. **[MEDIUM] `RequirementsAnalyzer.kt:115-118` — `LOCK_WRAPPER_SIMPLE`
   silently includes `computeWithAlternativeResolveEnabled`** as if it
   were a read-action wrapper. It is not — it's a DumbService helper
   that has nothing to do with read/write locks. Either drop it or
   document why.

8. **[LOW] `RequirementsAnalyzer.kt:152-155` — `TargetNotFound` message
   leaks "checked allScope" implementation detail.** The plan's
   `@McpDescription` promises `McpExpectedError("No class found for
   $fqn — checked allScope")` verbatim, so this is faithful to spec, but
   the wording is confusing for end-agents: `allScope` is an IntelliJ
   API term, not user vocabulary. Consider "checked project + libraries".

### Threading & timeouts

9. **[MEDIUM] `ArchitectureToolset.kt:461-488` — `readActionBlocking { ... }`
   wraps the analyzer call but no explicit `withTimeoutOrNull(10_000)` is
   applied around it.** Per CLAUDE.md hard rule, the platform's 10 s read-
   action timeout is the safety net; `readActionBlocking` already enforces
   that ceiling via its `DEFAULT_EDT_TIMEOUT_MS = 10_000L`. OK — but the
   analyzer's per-target `ReferencesSearch` loops inside the read action
   never check the elapsed time themselves; a single search that takes
   9 s can starve subsequent overload candidates of any analysis budget.
   Either reserve a per-target slice of the budget, or — minimally —
   record in the response a `partial=true` when the read action expires
   mid-loop (right now `truncated` only reflects `maxCallSites`, not
   timeouts).

### Design / API hygiene

10. **[MEDIUM] `PsiUsageSearcher.kt:304-344` — `callersOf` is dead code.**
    Either wire it into `RequirementsAnalyzer.analyze` (the plan's stated
    purpose for extracting it) or remove it. A public 40-line helper that
    nothing calls is exactly the kind of thing that rots in 3 months.

11. **[LOW] `RequirementsAnalyzer.kt:340-370` — Reflective duck-typing
    of Kotlin PSI (`getAnnotationEntries`, `getShortName`, `getCalleeExpression`,
    `getSelectorExpression`).** Same reasoning as the plan's "no hard
    Kotlin PSI dep" is fine in principle, but the existing codebase has
    no precedent for full-reflection PSI traversal — `PsiUsageSearcher`
    uses string simple-name matching for `isLocalVariableLike` and stops
    there. The reflection approach is fragile across Kotlin plugin
    versions (method-name renames break silently with `getOrNull()`).
    Consider adding a Kotlin-plugin compileOnly dep (like the existing
    `exec/` module) so you get static binding when the plugin is
    present, and graceful "no analysis" when it isn't.

12. **[LOW] `model/RequirementsInfo.kt:53-72` — `CheckRequirementsResponse`
    is missing the per-target reason channel (see finding #3).** Also
    `status` is a free-form `String` rather than the enum-shaped
    `RequirementKind` precedent set right above. Use a small sealed enum
    (`ok`/`mismatch`/`unknown`) — both for serialisation safety and so
    KSP renders typed values in `docs/MCP_TOOLS.md`.

13. **[LOW] `ArchitectureToolset.kt:373-378, 429-434` — the two tools
    duplicate 12 lines of boilerplate that could be one
    `runRequirementsCheck` call.** They already share `runRequirementsCheck`,
    which is good — but the only differences are
    `annotationFqns`/`wrapperKind`. Could be a thin enum-keyed pair if
    the duplication keeps growing.

14. **[LOW] `ArchitectureToolset.kt:454-458` — XOR check uses
    `hasTarget == hasPosition` to enforce "exactly one of".** Works,
    but emits a less-than-helpful error: `"specify target OR
    fileUrl+position"`. Better: differentiate "both supplied" from
    "neither supplied" since they're different user mistakes.

### Tests / fixtures

15. **[HIGH] No tests at all for `RequirementsAnalyzer`.** The plan's
    test plan calls out six fixture scenarios (A-F) explicitly; the PR
    ships none. No `src/test/testData/requirements/` directory, no
    `RequirementsAnalyzerPlatformTest.kt`. Given findings #1, #4, #5, #6
    above are all decision-tree bugs that a single fixture each would
    have caught, the analyzer is effectively unverified. Plan's effort
    estimate was 0.5 d for the fixtures + tests — this is the missing
    half.

### Things done right (no action needed)

- **`PositionResolver` extraction is exact.** All five
  `psi.*` call sites in `PsiToolset.kt` route through one private
  `resolveOffset` shim that delegates to the new helper — semantics
  preserved byte-for-byte vs. the pre-extraction code in commit
  `0c0bdb1^`.
- **Annotation FQNs hard-coded as constants.** No DevKit dep, matches
  plan open Q #1's resolution. `LOCK_ANNOTATION_FQNS` / `THREADING_…`
  partitioning is clean.
- **`McpDescription` text is verbatim from the plan, 5-section
  structure, examples included.** Faithful to plan and to CLAUDE.md
  conventions.
- **Read-action wrapping is correct.** Both new tools use
  `readActionBlocking { DumbService.computeWithAlternativeResolveEnabled
  { ... } }` — same pattern `psi.find_usages` uses (plan §"Threading,
  EDT, timeout").
- **`JavaModuleUnavailable` surfaces as `McpExpectedError` rather than
  `NoClassDefFoundError`.** Nice touch for IDEs without Java
  (RustRover, GoLand).
- **`ProcessCanceledException` re-thrown explicitly in every catch
  block.** PCE propagation matters for 10 s timeout; the analyzer
  honours it.

## Recommended changes (priority order)

1. Add the six fixtures from the plan's test plan A-F + the
   `RequirementsAnalyzerPlatformTest` (#15). Without this every other
   finding is theoretical.
2. Fix the `@RequiresReadLockAbsence` + `nonBlocking` / wrapper-set
   drift (#1) — single source-of-truth for which wrapper qualifies for
   which contract.
3. Route the override-search path through `PsiUsageSearcher.callersOf`
   or delete the dead helper (#10, #2).
4. Add a `targetReason` (or remove the `"no contract"` dead branch) so
   the agent can distinguish "no contract" from "no callers" (#3, #12).
5. Tighten `isInsideOpaqueDispatcher` to one pass (#6).

## File-touch summary

| File | Verdict |
|------|---------|
| `core/RequirementsAnalyzer.kt` (new, 715 LOC) | Needs decision-tree fixes (#1, #4, #5, #6); shape OK |
| `model/RequirementsInfo.kt` (new, 73 LOC) | Add `targetReason` + enum-typed `status` (#3, #12) |
| `model/args/ArchArgs.kt` (+32 LOC) | OK; minor message tweak (#14) |
| `tools/ArchitectureToolset.kt` (+201 LOC) | OK; consider partial-results signal (#9) |
| `util/PositionResolver.kt` (new, 42 LOC) | Faithful extraction — ship as-is |
| `core/PsiUsageSearcher.kt` (+50 LOC) | Dead code; wire in or delete (#10) |
| Tests | Missing — block on this (#15) |
