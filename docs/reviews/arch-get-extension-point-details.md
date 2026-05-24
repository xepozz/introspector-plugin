# Review: arch.get_extension_point_details

Branch: `claude/project-features-analysis-odEwP` @ HEAD (`90ac549`)
Scope:
- `src/main/kotlin/com/github/xepozz/ide/introspector/core/ExtensionPointInspector.kt`
  (extended — `getDetails`, `resolveExtensionClass`, `harvestBeanSchema`,
  `describeBeanField`, `harvestInterfaceMethods`, plus helpers)
- `src/main/kotlin/com/github/xepozz/ide/introspector/model/ExtensionPointDetails.kt` (new)
- `src/main/kotlin/com/github/xepozz/ide/introspector/tools/ArchitectureToolset.kt`
  (`arch_get_extension_point_details` `@McpTool`)
- `src/test/kotlin/.../core/ExtensionPointDetailsTest.kt` (new)
- `src/test/kotlin/.../core/platform/ExtensionPointDetailsPlatformTest.kt` (new)

Plan: `docs/plans/arch-get-extension-point-details.md`.

## Verdict

**Ship-ready.** Architecture is right — pure reflection, never touches
`extensionList`, uses `ep.size()` for the adapter count, accepts both pre-
and post-252 `@RequiredElement` packages, falls back to class-name
inspection for `kindAndClass` now that `ExtensionPointImpl.getKind()` is
gone from 252, and the platform test cleverly iterates a candidate list
so the INTERFACE assertion survives platform churn. The two findings
below are MEDIUM (documentation/regression-net) — neither blocks merge.

## Findings

### Correctness

1. **[MEDIUM] `ExtensionPointInspector.kt:362-365` — accepts the wrong
   legacy `@RequiredElement` FQN.** The fallback list is:
   ```kotlin
   val requiredAnn = findAnnotation(f, "com.intellij.openapi.extensions.RequiredElement")
       ?: findAnnotation(f, "com.intellij.util.xmlb.annotations.RequiredElement")
   ```
   But `@RequiredElement` has lived in `com.intellij.openapi.extensions`
   for years (verified at `idea/243.22562.145` and on `master` —
   `platform/core-api/src/com/intellij/openapi/extensions/RequiredElement.java`).
   It was never in `com.intellij.util.xmlb.annotations`. The fixer's
   note about a "package change in 252" appears to be a misread — the
   modern FQN check is correct, the legacy fallback is fictional and
   will never match anything. Two consequences:
   - Comment block at lines 361-363 ("The platform moved
     `@RequiredElement` out of `com.intellij.util.xmlb.annotations` and
     into `com.intellij.openapi.extensions` somewhere around 252") is
     factually wrong and will mislead future maintainers.
   - The fallback string is dead code — easy to delete.
   Recommend: keep only the `com.intellij.openapi.extensions.RequiredElement`
   probe, drop the second `?:` branch, and replace the comment with a
   one-liner noting the stable FQN. Not a runtime bug because the live
   path works; cleanup, not a defect.

2. **[MEDIUM] Type rendering disagrees with the plan and produces
   noisy FQNs for primitives/strings.** Plan §5 ("Generic field types
   — record raw type, e.g. `List<String>` → `List`") says simple names.
   The unit test (`ExtensionPointDetailsTest.kt:34`) asserts
   `"java.lang.String"`. Impl at `ExtensionPointInspector.kt:405`:
   ```kotlin
   type = f.type.name,
   ```
   `f.type.name` produces `"java.lang.String"`, `"boolean"`,
   `"[Ljava.lang.String;"` for arrays. For `MethodSig.returnType`
   (`harvestInterfaceMethods.kt:434`) the impl correctly uses
   `m.returnType.simpleName`. So bean schemas and interface methods
   render the type field with different conventions — agents looking
   at one response have to decode `java.lang.String` and `String`
   from the same `type` slot depending on which side. The plan is
   the more useful contract: use `simpleName` (or
   `canonicalName?.substringAfterLast('.')` for arrays). The unit
   test then needs `"String"` not `"java.lang.String"`. Trivial fix
   — bigger payoff is consistency across the response. Optional
   middle ground: keep `f.type.name` but explicitly call out the
   FQN convention in the `BeanField.type` kdoc.

### Reflection-correctness (cross-checked against IntelliJ master)

3. **[OK] `ep.size()`, never `extensionList`.** `ExtensionPointInspector.kt:260`
   uses `try { ep.size() } catch (_: Throwable) { 0 }`. Matches the
   CLAUDE.md pitfall. The new code also never reads
   `extensionList` in any path. Confirmed by grep — no occurrence of
   `extensionList` anywhere in the added methods.

4. **[OK] `getKind()` removal in 252 is handled.** `kindAndClass` now
   tries `getKind()` reflectively, then falls back to
   `kindFromClassName(ep.javaClass)` walking up to `InterfaceExtensionPoint`
   / `BeanExtensionPoint`. Verified against master:
   `InterfaceExtensionPoint` and `BeanExtensionPoint` are the two
   `internal class … : ExtensionPointImpl<T>` subclasses; neither
   `BeanExtensionPoint.kt` nor `InterfaceExtensionPoint.kt` overrides
   `getKind` — the public accessor is gone from `ExtensionPointImpl`
   too (the `kind` field is private to `ExtensionPointImpl<T>`). The
   walker correctly finds the subclass name. ✅

5. **[OK] `@Attribute.value()` reflection is correct.** The annotation
   defines `value: String = ""` and `converter: Class<? extends
   Converter> = Converter.class` (`platform/util/src/com/intellij/util/
   xmlb/annotations/Attribute.java` on master). `readAnnotationStringValue`
   reads `value()` and treats empty-string as the sentinel ("use the
   field name"), exactly matching XmlSerializer semantics. The
   `@McpDescription` text says `@Attribute(name=…)` (no such member —
   only `value()`), but the impl never reads `name()`, so this is
   purely a docstring wart, not a bug. Same for `@Tag` (only `value`
   and `textIfEmpty`).

6. **[OK] `@RequiredElement` modern FQN is correct.**
   `com.intellij.openapi.extensions.RequiredElement` — verified at
   `platform/core-api/src/com/intellij/openapi/extensions/RequiredElement.java`
   on master and on idea/243.22562.145. (The legacy fallback is wrong
   but harmless — see finding 1.)

7. **[OK] `getExtensionClass()` lazy-resolved Class<*> reuse.** The
   modern `ExtensionPointImpl` field is `private var extensionClass:
   Class<T>?` with a `getExtensionClass()` accessor — exactly what
   `resolveExtensionClass(ep, fqn)` calls reflectively first, then
   falls back to `Class.forName(fqn, false, pluginClassLoader)`. The
   `false` "don't initialize" arg means we only force loading the
   class metadata, not its static initialisers — same safety stance
   as the platform's own lazy resolve. ✅

### Bean schema (`harvestBeanSchema`)

8. **[OK] Walks superclass hierarchy, dedupes by field name, caps at
   maxFields, sets `truncated=true` when cap hits.** Matches plan
   §"Reflection pattern for bean schema". `seen.add(f.name)` hides
   Kotlin synthetic shadows (which `f.isSynthetic` also filters).

9. **[OK] Public unannotated → `xmlAttributeName = f.name`, private
   unannotated skipped, `@Tag` nests (clears `xmlAttribute`),
   `@Property(style=TAG/ATTRIBUTE)` switched correctly, `@Deprecated`
   (Java + Kotlin via `isPropertyDeprecated`) detected.** All
   covered by tests. `kotlin-reflect` last-resort in
   `isPropertyDeprecated` is `try/catch`-gated. `@Property` without
   `style` is untested — acknowledged via `keepPropertyImportReachable`
   at line 215.

10. **[LOW] `@Attribute` with no explicit `value()` is untested.**
    The unit suite covers `@Attribute("id")` (explicit value) and
    `@JvmField var plainPublic` (no annotation at all). It does not
    cover the very common case `@Attribute @JvmField var anchor:
    String` (annotation present, value defaulted). The XmlSerializer
    rule is "default to field name", which the impl implements via
    `readAnnotationStringValue(attrAnn) ?: f.name`. The platform
    test exercises this implicitly via ToolWindowEP.anchor (which
    is `@Attribute public String anchor` per upstream source —
    verified), but unit coverage would catch a future regression
    that breaks the empty-string sentinel.

### Interface methods (`harvestInterfaceMethods`)

11. **[OK] Filters to abstract, skips Object overrides + bridge/
    synthetic, dedupes by `name+signature`, sorts by name.** Matches
    plan §"Interface methods". `returnType` uses `simpleName` —
    consistent within MethodSig, inconsistent with BeanField.type
    (see finding 2).

12. **[LOW] `@ApiStatus.Internal` methods are NOT filtered.** Plan
    §6 only excludes the *interface* from filtering; doesn't say
    anything about methods. A one-line filter on
    `org.jetbrains.annotations.ApiStatus.Internal` would stop agents
    from suggesting that contributors implement methods JetBrains
    intends to remove. Optional / v2.

### Tool wiring, threading, `@McpDescription`

13. **[OK] Tool method at `ArchitectureToolset.kt:161-178` is pure
    delegation, no EDT bounce, no `requireProject()` (correct — EPs
    live on the application area too).** `@McpDescription` follows
    the 5-section template; examples cover all three regimes (bean
    schema, bean+count, interface methods); Returns section fully
    types the JSON shape. Only nit: line 151 says `@Attribute(name=…)`
    but the annotation only defines `value()` — cosmetic, matches
    the plan's prose.

14. **[OK] `runCatching` wraps both harvest calls
    (`getDetails:270-275`) — broken third-party bean degrades to
    partial response, never an exception. Sub-100 ms reflection is
    well under the 10 s cap; no `withTimeoutOrNull` needed.**

### Test coverage

15. **[OK] Unit tests (12 schema + 4 interface) cover the decision
    matrix: explicit `@Attribute` rename, public-only/private-skip/
    static-skip, `@Tag` nesting, `@Deprecated`, maxFields truncation,
    superclass walk, `className` equality. Fills plan §"Test plan /
    Unit" exactly.** The `@JvmField` strategy on every fixture
    side-steps the Kotlin-annotation-on-getter wart cleanly.

16. **[OK] Platform test correctly pivots to a candidate list per
    fixer note.** `testInterfaceExtensionPointReturnsInterfaceMethods`
    iterates `["com.intellij.errorHandler", "com.intellij.statusBarWidgetFactory",
    "com.intellij.applicationService", "com.intellij.codeInsight.
    lineMarkerProvider"]` and returns on first match. Of those,
    `com.intellij.codeInsight.lineMarkerProvider` is INTERFACE
    (`LineMarkerProvider` is the interface) AND universally
    registered in any IDE that ships the platform-impl module —
    so coverage is real, not theoretical. `com.intellij.toolWindow`
    is correctly absent from the list (it's BEAN_CLASS via
    `ToolWindowEP`, not the interface). The `assumeTrue(checkedAny)`
    skip path is a safety net, not an unreachable branch.

17. **[LOW] The candidate-list test produces zero coverage of the
    INTERFACE branch on the (unlikely) platform build where all
    four candidates miss.** `assumeTrue(false)` skips quietly —
    silent CI green with no INTERFACE assertion. Strengthen by
    upgrading the safety net to a hard `fail("None of $candidates
    were registered — please update the candidate list")`. The
    candidate list is curated; if it stops matching anything we want
    a loud signal.

18. **[OK] `testIncludeRegisteredCountMatchesAdapterCount` /
    `testIncludeRegisteredCountDefaultsToNull` lock down `ep.size()`
    AND the nullable-flag default. `testUnknownEpReturnsNullInsteadOfThrowing`
    locks the graceful-null contract.**

### Plan-vs-impl gaps

19. **`maxFields` "per side" wording is slightly misleading** — only
    one side is ever populated per response (kind decides). Cosmetic.

20. **`area` correctly prefers application** (`locateEpWithArea` —
    app loop first, project loop second). Plan §"Edge cases" #10
    satisfied.

21. **[INFO] `interfaceOrBeanClass` uses `"?"` as an in-band
    sentinel when unresolvable.** Matches `ExtensionPointInfo`
    convention. Agents that want to detect resolve-failure have
    to string-compare.

## Research notes

- **`ExtensionPointImpl` in 252+**:
  `final override fun size(): Int = adapters.size` — adapter count,
  no instantiation. `private var extensionClass: Class<T>?` —
  lazy-resolved via `getExtensionClass()`. `getKind()` is **gone**
  from `ExtensionPointImpl`; the `kind` distinction is encoded by
  the concrete subclass (`InterfaceExtensionPoint` / `BeanExtensionPoint`).
  Source: `platform/extensions/src/com/intellij/openapi/extensions/
  impl/ExtensionPointImpl.kt` on master.
- **`ExtensionPoint.Kind` enum**: `enum Kind {INTERFACE, BEAN_CLASS}`
  on the public `ExtensionPoint` interface in
  `platform/extensions/src/com/intellij/openapi/extensions/ExtensionPoint.java`
  — names stable since 2019, safe to compare against the raw
  `kindStr`.
- **`@RequiredElement` package**: `com.intellij.openapi.extensions`
  on master AND on `idea/243.22562.145` — never lived in
  `com.intellij.util.xmlb.annotations`. The fixer's "package change
  in 252" note is incorrect. (See finding 1.)
- **`@Attribute`**: `package com.intellij.util.xmlb.annotations;
  public @interface Attribute { String value() default ""; Class<?
  extends Converter> converter() default Converter.class; }` — only
  `value` and `converter`. No `name()`.
- **`@Tag`**: same package — `value: String = ""` and
  `textIfEmpty: String = ""`. Only `value` is read by the impl;
  `textIfEmpty` ignored, which is correct (only affects rendering,
  not schema).
- **`ToolWindowEP`** (`platform/platform-api/src/com/intellij/
  openapi/wm/ToolWindowEP.java`): `@RequiredElement @Attribute
  public String id; @Attribute public String anchor; @RequiredElement
  @Attribute public String factoryClass; @Attribute("icon") public
  String icon;` — confirms the platform test's expected fields and
  exercises the empty-`value()` path via `anchor`.

## References

- `ExtensionPointImpl.kt` (master):
  https://github.com/JetBrains/intellij-community/blob/master/platform/extensions/src/com/intellij/openapi/extensions/impl/ExtensionPointImpl.kt
- `ExtensionPoint.java` (`Kind` enum, master):
  https://github.com/JetBrains/intellij-community/blob/master/platform/extensions/src/com/intellij/openapi/extensions/ExtensionPoint.java
- `RequiredElement.java` (243 tag — same content on master):
  https://github.com/JetBrains/intellij-community/blob/idea/243.22562.145/platform/core-api/src/com/intellij/openapi/extensions/RequiredElement.java
- `Attribute.java` (master):
  https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/util/xmlb/annotations/Attribute.java
- `Tag.java` (master):
  https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/util/xmlb/annotations/Tag.java
- `ToolWindowEP.java` (master):
  https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/openapi/wm/ToolWindowEP.java
- CLAUDE.md "Common pitfalls" — `ep.size()` vs `ep.extensionList.size`.
- Plan: `/home/user/ide-introspector-plugin/docs/plans/arch-get-extension-point-details.md`
- Implementation commit: `275030b feat: salvage arch-get-extension-point-details (untested)`
- Fixer commit: `733906d fix(phase3): make salvaged features compile/test green`
