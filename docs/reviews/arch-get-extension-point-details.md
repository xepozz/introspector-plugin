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

8. **[LOW] `resolveExtensionClass` plugin-classloader fallback can
   leak through to the application classloader when the EP belongs
   to a disabled plugin.** `Class.forName(fqn, false,
   pluginClassLoader)` first; on `Throwable` falls through to
   `Class.forName(fqn, false, ExtensionPointInspector::class.java.classLoader)`.
   Mostly fine because the application classloader can see most
   platform bean classes. Edge case: if a plugin-supplied bean shares
   a simple name with a platform class on a different ClassLoader,
   the fallback returns the *wrong* class. Unlikely in practice
   (bean classes are typically `com.intellij.*` or
   `com.example.myplugin.*` — namespaced). No fix required; flagging
   for the record.

### Bean schema (`harvestBeanSchema`)

9. **[OK] Walks superclass hierarchy, dedupes by field name, caps at
   maxFields, sets `truncated=true` when cap hits.** Matches plan
   §"Reflection pattern for bean schema". The `seen.add(f.name)`
   guard correctly hides Kotlin's `$$delegate_*` synthetic fields
   (which themselves are filtered by `f.isSynthetic`).

10. **[OK] Public unannotated fields included with `xmlAttributeName
    = f.name`, private unannotated skipped.** Mirrors XmlSerializer
    exactly. The `Modifier.isPublic(f.modifiers)` check at line 397
    is the right gate. Test case
    `harvestBeanSchema skips private unannotated fields and static fields`
    confirms.

11. **[OK] `@Tag` nests, clears `xmlAttribute`.** Line 384:
    `xmlAttribute = null` when `tagAnn != null`. The `nested` field
    test (`harvestBeanSchema renders @Tag as nested-element entry`)
    locks this in.

12. **[OK] `@Property(style=TAG/ATTRIBUTE)` reflection.** Reads the
    enum member by name and switches. No test covers this branch
    (acknowledged via `keepPropertyImportReachable` at line 215),
    but the logic is straightforward — `@Property` without `style`
    correctly falls through to the default attribute path.

13. **[OK] `@Deprecated` (Kotlin or Java) detection is over-engineered
    but correct.** `isPropertyDeprecated` walks `getX` / `isX` / `setX`
    / `x$annotations` candidates and finally falls back to
    `cls.kotlin.members`. The `kotlin-reflect` last-resort is gated by
    `try/catch` — won't crash if `kotlin-reflect` is missing at
    runtime. Comment block at lines 461-466 documents the rationale.

14. **[LOW] `BeanField.type = f.type.name` doesn't unwrap generics.**
    The plan calls this out as the intended choice ("record raw
    `f.type.name`; generic info isn't needed for XML schema"), and
    the kdoc on `BeanField` says "raw type (no generics)" — matches.
    Only flagging because finding 2 conflicts with finding 14's
    reading: if you fix finding 2 to use `simpleName`, the kdoc
    needs an update too. Pick one of (a) FQN like `java.lang.String`,
    (b) simple name like `String` — but don't mix.

15. **[LOW] `@Attribute` with no explicit `value()` is untested.**
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

16. **[OK] Filters to abstract, skips Object overrides + bridge/
    synthetic, dedupes by `name+signature`, sorts by name.** Matches
    plan §"Interface methods" exactly. The `m.declaringClass ==
    Any::class.java` check at line 423 is correct — `cls.methods`
    returns the full inherited set, so we need to filter Object.

17. **[OK] `MethodSig.returnType = m.returnType.simpleName`.** Uses
    simple name (consistent within MethodSig, inconsistent with
    BeanField.type — see finding 2).

18. **[LOW] `@ApiStatus.Internal` is NOT filtered out.** Plan §6
    edge case asks: "INTERFACE EP whose interface is
    @ApiStatus.Internal or an inner class — still reflectable; don't
    filter out (caller asked by name)." Confirmed — no filter. Good.
    But the plan also doesn't say to filter *methods* annotated
    `@ApiStatus.Internal`. Worth a one-line filter (`findAnnotation(m,
    "org.jetbrains.annotations.ApiStatus.Internal") == null`) so an
    agent generating an EP implementation doesn't write code against
    methods JetBrains intends to remove. Optional / v2.

### Tool wiring & `@McpDescription`

19. **[OK] Tool method is pure delegation.**
    `ArchitectureToolset.kt:161-178` — straight pass-through to
    `ExtensionPointInspector.getDetails`. No EDT bounce (correct —
    `arch.*` doesn't need one). No `requireProject()` call (correct
    — EPs live on the application area too).

20. **[OK] `@McpDescription` follows the 5-section template.** What
    / Use when / Do NOT use / Returns / Examples — all present and
    runnable. The examples cover all three regimes (BEAN_CLASS with
    schema, BEAN_CLASS with count, INTERFACE with methods). Returns
    section fully types the JSON shape including optional fields.

21. **[LOW] Docstring at line 151 says `@Attribute(name=…)` but the
    annotation only defines `value()`.** Cosmetic. Aligns with the
    plan's prose (which makes the same slip). Recommend "the
    @Attribute("foo") override" or "@Attribute value override".

### Threading & caching

22. **[OK] No EDT bounce, no `withTimeoutOrNull`, no cache.** Plan
    §"Threading & EDT model" justified all three. Reflection on a
    single class hierarchy is sub-100 ms — well under the 10 s cap.
    `runCatching` wraps both harvest calls (`getDetails:270-275`) so
    a broken third-party bean degrades to a partial response rather
    than an exception. ✅

### Test coverage

23. **[OK] Unit tests cover the schema-side decision matrix:** explicit
    `@Attribute("id")` rename, `@JvmField` public-only path, private
    skip, static skip, `@Tag` nesting, `@Deprecated`, maxFields
    truncation, superclass walk, `className` equality. 12 tests + 4
    interface-method tests — comprehensive for the bean-side. Fills
    plan §"Test plan / Unit" exactly.

24. **[OK] Platform test pivots from a single brittle EP to a
    candidate list.** `testInterfaceExtensionPointReturnsInterfaceMethods`
    iterates `["com.intellij.errorHandler", "com.intellij.statusBarWidgetFactory",
    "com.intellij.applicationService", "com.intellij.codeInsight.
    lineMarkerProvider"]` and returns on the first that resolves as
    INTERFACE with at least one abstract method. The
    `assumeTrue(checkedAny)` path correctly skips rather than fails
    when none match — exactly the right shape per the fixer note.
    The ToolWindowEP test uses a `⊇` subset check (`for (expected
    in listOf("id", "anchor", "factoryClass"))`) — survives the
    platform adding new fields. Both shapes match the plan §"Test plan
    / Platform" intent.

25. **[LOW] Candidate list in `testInterfaceExtensionPointReturnsInterfaceMethods`
    skips `com.intellij.toolWindow` even though that's the
    canonical INTERFACE-ish example.** `com.intellij.toolWindow` is
    BEAN_CLASS (it's `ToolWindowEP`, not `ToolWindowFactory`), so the
    candidate list does the right thing. But the test name "INTERFACE
    EP returns interface methods" combined with the candidate list
    that doesn't *guarantee* any candidate is registered (`assumeTrue`
    skip path) means CI could quietly produce zero coverage of the
    INTERFACE branch on some platform builds. Strengthen by adding
    a more universally-registered INTERFACE EP candidate at the
    front of the list:
    - `com.intellij.fileTypeFactory` (deprecated but still registered
      on most builds)
    - `com.intellij.iconMapper`
    - `com.intellij.projectService` is NOT an EP — services aren't
      reachable via `ExtensionPointInspector.getDetails`. Skip.
    Or commit to one of the existing candidates with a hard
    `assertNotNull` and document "if this skips, file an issue".
    Currently the test passes with zero assertions if all candidates
    miss.

26. **[OK] `testIncludeRegisteredCountMatchesAdapterCount` /
    `testIncludeRegisteredCountDefaultsToNull` lock down the
    `ep.size()` path AND assert it's NOT populated when opted out.**
    Good — exactly the kind of nullable-flag verification that catches
    a careless future refactor that always populates the field.

27. **[OK] Unknown-EP path returns null.**
    `testUnknownEpReturnsNullInsteadOfThrowing` — present, asserts
    `assertNull`. Matches plan §"Edge cases" #1.

### Plan-vs-impl gaps

28. **`maxFields` parameter applies to bean fields AND interface
    methods (one cap, two side-by-side branches).** Plan §"Signature"
    docstring says "Hard cap on bean fields / interface methods
    returned (per side)". The kind+include flags mean only one side
    is ever populated, so the "per side" wording is slightly
    misleading — there's no scenario where both are non-null. Cosmetic.

29. **The plan promises a `BeanSchema.truncated = true` set when the
    `extensionClass` is unresolvable — but a totally-unresolvable
    class today produces `beanSchema = null` (because `cls = null`
    skips the harvest entirely), not `BeanSchema(_, [], truncated =
    true)`.** Plan edge case 8: "`extensionClass` unresolvable
    (broken plugin / classloader miss) — catch, omit `beanSchema`/
    `interfaceMethods`, keep descriptor populated." Confirmed —
    impl returns the descriptor without `beanSchema`. So no plan
    violation, just verifying the cited path.

30. **`area` always returns the FIRST area that registered the EP**
    (`locateEpWithArea` — app first, then loop open projects).
    Plan §"Edge cases" #10: "Same name in both app and project area
    — prefer application; record `area`." Confirmed: app loop runs
    first, project loop only fires if app missed.

### Cross-cutting

31. **[INFO] No equivalent of `arch.list_extensions_for_ep` integration.**
    A natural follow-up — `getDetails` does not return any extension
    list. Plan explicitly punts this to a follow-up (`arch.list_
    extensions_for_ep` already exists and is a one-call combo). No
    action.

32. **[INFO] `interfaceOrBeanClass` is non-nullable, populated with
    `"?"` when unresolvable.** Matches the existing `ExtensionPointInfo`
    contract — consistent. An agent that wants "did the class
    resolve?" has to test for `"?"`. Acceptable; flagging only
    because the JSON shape uses an in-band sentinel.

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
