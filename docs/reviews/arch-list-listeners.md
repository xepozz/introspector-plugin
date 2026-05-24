# Review: arch.list_listeners

Branch: `claude/project-features-analysis-odEwP` @ HEAD (`3c15123`)
Scope: `core/ListenerInspector.kt`, `model/ListenerInfo.kt`,
`model/args/ArchArgs.kt` (new `ListListenersArgs`), the new `@McpTool`
method on `tools/ArchitectureToolset.kt`, plus
`src/test/kotlin/.../core/ListenerInspectorReflectionTest.kt` and
`src/test/kotlin/.../core/platform/ListenerInspectorPlatformTest.kt`.
Plan: `docs/plans/arch-list-listeners.md`.

## Verdict

**Ship after one small fix.** The implementation faithfully executes the plan
— reflective walk reusing `ExtensionPointInspector.readField`, fallback chain
for both container names and listener field names, per-plugin attribution
via `descriptor.pluginId.idString`, all flags preserved with the right
defaults, `TtlCache` 60 s, args validation with the `McpExpectedError`
path, and a serious test suite (10 pure-JVM cases + 7 platform cases).
The only real defect is that the plan’s "log a `thisLogger().debug` on
first per-build container miss" early-warning hook never landed, so a future
platform field rename will produce a silent empty list rather than a debug
trail — exactly the failure mode the plan called out (open question #2).

## Findings

### Correctness

1. **[OK] Container-field fallback chain implemented correctly.**
   `ListenerInspector.kt:65-70` tries `appContainerDescriptor` then `app`,
   and `projectContainerDescriptor` then `project`, in that order via a
   `Sequence.mapNotNull { readField(...) }.firstOrNull()`. Modern field
   names confirmed against current master
   (`IdeaPluginDescriptorImpl.kt` declares `val appContainerDescriptor`
   / `val projectContainerDescriptor` / `val moduleContainerDescriptor`
   as Kotlin properties; the file is `.kt`, not `.java` as the plan
   text suggests). The shorter `app` / `project` aliases are the
   historic naming the plan documents; preserving them is cheap
   insurance for older 251.x / Android Studio forks.

2. **[OK] `readField` reuse is correct.** The new inspector calls
   `ExtensionPointInspector.readField` (already `internal` per
   `ExtensionPointInspector.kt:577`) rather than reinventing the
   field-walking loop. Matches the codebase convention (`ServiceInventory`
   also imports it via `readField` / `readMethod`). The plan’s
   "promote `readField` to internal" recommendation is moot — already done.
   Note: the lower-level `ExtensionMetadata` helper in
   `core/internal/ExtensionMetadata.kt` is for *bean-attribute* harvesting
   (a different problem — surveys all fields of an instance), so reusing
   `readField` from `ExtensionPointInspector` rather than a new helper
   in `internal/` is the right call.

3. **[OK] Per-plugin attribution.** `collect()` calls
   `descriptor.pluginId.idString` and `descriptor.name` directly on the
   `IdeaPluginDescriptor` interface (`ListenerInspector.kt:41-42`) — the
   stable public surface, no reflection needed for this part. Each
   listener is appended with that `pluginId` / `pluginName` (lines 116-121),
   so a `<applicationListeners>` declaration in plugin Y is correctly
   attributed to Y rather than to `"unknown"` — the same trap that
   `ExtensionPointInspector` warns about for
   `ExtensionComponentAdapter.pluginDescriptor` (CLAUDE.md pitfalls).

4. **[OK] `activeInTestMode` / `activeInHeadlessMode` flag handling.**
   Both default to `true` when the reflective read returns null
   (`ListenerInspector.kt:113-114`), matching the platform XML default
   (per the JetBrains "Listeners" doc: *"Set to false to disable
   listener if Application.isUnitTestMode() returns true"* — the field
   is absent on most declarations, meaning enabled). The unit test
   `respects explicit activeInTestMode and activeInHeadlessMode flags`
   exercises the explicit-false branch; the happy-path test asserts the
   absent-defaults-true branch.

5. **[OK] Scope filter contract.** `arch_list_listeners` rejects
   `scope` outside `{application, project, both}` with `McpExpectedError`
   (`ArchitectureToolset.kt:619-624`), matching the plan’s stricter
   validation policy (and *unlike* `arch_list_extension_points` which
   silently accepts `area="both"`). The `VALID_LISTENER_SCOPES` companion
   constant is a nice touch — readable and easy to extend.

6. **[OK] Programmatic listeners out of scope, documented.**
   The `@McpDescription` "Do NOT use this when…" clause names
   `MessageBus.connect().subscribe(...)` explicitly. The plan’s open
   question on `MessageBusImpl.topicsBySubscriberMap` is correctly
   deferred — the brittleness call (`@ApiStatus.Internal`, field
   churn, classloader walk to attribute back to a plugin) is sound.
   No code touches `MessageBus` internals.

7. **[OK] TtlCache wisely tuned.** 60 s TTL on a list that only changes
   on dynamic-plugin load is appropriate; matches the
   `ActionInventory` cache TTL and is 2× the `PluginInventory` TTL
   (30 s) — both reasonable given listeners change strictly less
   often than the wider plugin set. Singleton via
   `@Service(Service.Level.APP)`, same shape as `PluginInventory`.

8. **[OK] Edge cases covered.**
   - Non-`IdeaPluginDescriptorImpl` descriptor (custom forks / test stubs):
     `collectScope` returns early when neither container field resolves
     (`ListenerInspector.kt:82-83`). Test
     `skips plugin entirely when app and project containers are missing` ✓.
   - Container present but `listeners` field is `null`: handled at
     `ListenerInspector.kt:84` — `as? List<*> ?: return`. Tested via
     `handles container with null listeners field` ✓.
   - `null` element inside the listeners list: skipped at
     `ListenerInspector.kt:86`. Tested via
     `tolerates null entries inside the listeners list` ✓.
   - Missing `topicClassName` or `listenerClassName`: returns `null`
     and the row is dropped (`toListenerInfoOrNull` lines 107-112).
     Both tested ✓.
   - Per-plugin `try/catch` at `collect()` (`ListenerInspector.kt:40-46`)
     means a single broken descriptor cannot abort the whole walk —
     it gets logged at debug and skipped. Good defensive shape.

### Issues

9. **[MED] Plan-required "log on first container miss per build" is
   missing.** Plan §"Open questions / risks" #2 reads (line 273-275):
   *"reflective `readField` returning `null` is silent. Add a
   `thisLogger().debug` line on the first `null` per build so a verifier
   run surfaces the issue rather than producing an empty list quietly."*
   Today, if both `appContainerDescriptor` and `app` come back `null`
   (i.e. JetBrains shipped a third rename in a future build),
   `collectScope` returns silently — every call yields an empty list
   and the only visible signal is the platform test failing on the
   CI verifier run. That’s acceptable in CI but invisible to anyone
   running the tool live against an unsupported IDE build. Cheap
   fix: a one-shot `AtomicBoolean` per scope guarding a
   `thisLogger().warn("[ListenerInspector] neither 'appContainerDescriptor'
   nor 'app' resolved on ${descriptor.javaClass.name}; field rename?")`.
   Avoid spamming the log — the static flag pattern is the same
   `ExtensionPointInspector` uses elsewhere.

10. **[LOW] Per-listener-descriptor field-rename detection only happens
    when *both* canonical fields are absent, not when an inconsistent
    shape mixes them.** `toListenerInfoOrNull` (lines 107-112) reads
    `topicClassName` first, falls back to `topicClass`. Good. But a
    descriptor that has `topicClassName` but not `listenerClassName`
    (mixed shape) is dropped silently because the second `?: return null`
    fires. In practice this won’t happen — the platform rename, if it
    occurs, will rename both fields atomically — but consider the
    debug-log option from finding 9 here too: when one resolves and
    the other doesn’t, that’s an actionable signal worth logging.
    Not blocking.

11. **[LOW] `ListenerInfo.scope` is a free-form String.** The model
    declares `val scope: String` with a kdoc *"application | project"*
    but nothing enforces it. The same pattern lives elsewhere in the
    codebase (`ServiceInfo.scope`) so this is consistent — flagging
    only as a future cleanup candidate (a sealed `enum` would be a
    minor breaking change to a JSON schema, so probably not worth
    it solo). Not blocking.

12. **[LOW] Plan §Edge case 6 ("duplicate `topicClass + listenerClass +
    scope` rows across two plugins: keep both") is honoured implicitly
    but never *tested*.** The `collectFromDescriptor` design preserves
    duplicates correctly (it just appends), but there’s no explicit
    test asserting that two stub descriptors with the same topic/listener
    pair produce two `ListenerInfo` rows with distinct
    `providedByPluginId`. Two-line test, would lock the contract down.

### Threading / EDT

13. **[OK] No EDT bouncing needed.** `PluginManagerCore.plugins` and the
    `IdeaPluginDescriptorImpl` graph are immutable post-startup; this
    matches the existing `PluginInventory` / `ExtensionPointInspector`
    /`ServiceInventory` pattern documented under CLAUDE.md "Threading"
    ("`ExtensionPoint` enumeration is thread-safe — `arch.*` tools
    don't need EDT bouncing"). The `suspend fun` runs on the ktor
    coroutine, the inspector is a plain function call. Correct.

14. **[OK] 10 s timeout policy.** No `withTimeoutOrNull` / `Future.get`
    / latch wrappers. Inspector is pure reflection; worst case
    ~150 plugins × ~5 listeners average = ~750 field reads. Below
    the threshold where a timeout would be warranted, consistent with
    the plan’s "no timeout wrapping needed" position. Compliant with
    CLAUDE.md "Timeouts: 10 s cap, no exceptions" because no async
    operation exists in this code path at all.

### Test coverage

15. **[OK] Reflection test is thorough.** Ten cases across four
    sections, covering happy path (single + both scopes), explicit
    flags, `os` attribute, all four early-return branches
    (`topicClassName=null`, `listenerClassName=null`, no `app`/`project`
    fields, `listeners=null`), null-in-list tolerance, the field-rename
    fallback, and direct `toListenerInfoOrNull` invocations. The
    `@JvmField` stub trick (lines 263-291) is the right way to drive
    reflection-based code from a pure-JVM harness without spinning up
    the IntelliJ runtime — same approach `ServiceInventoryReflectionTest`
    uses.

16. **[OK] Platform test asserts the right invariants.** Seven cases:
    non-empty, ≥1 platform-attributed listener, scopes constrained,
    ≥1 app-scope listener, field population non-blank, cache same-instance
    within TTL, refresh invalidates. The `assumeTrue` guards on
    `testAtLeastOneApplicationScopeListenerExists` and
    `testListenerInfoFieldsArePopulated` are correct — they keep the
    test green on a hypothetical IDE build that ships zero listeners
    rather than blocking the pipeline. The `setUp().refresh()`
    guarantees test isolation from any prior test that warmed the
    cache. Good shape.

17. **[INFO] No "filter `topicContains = NoSuchTopicXYZ` returns empty
    without throwing" test as the plan suggested (test plan bullet 4).**
    The platform test asserts the inspector layer, not the toolset
    layer, so the filter is exercised only at the
    `ArchitectureToolset.arch_list_listeners` boundary — which has no
    test today. Worth a one-screen platform test asserting (a) the
    `McpExpectedError` for `scope="invalid"`, (b) `topicContains=
    "NoSuchTopicXYZ"` returns empty `listeners` with `total=0`, and
    (c) a known platform topic (e.g. anything containing
    `"FileEditorManagerListener"`) returns ≥1 result. Not blocking;
    the inspector contract is the load-bearing one and that *is* covered.

### Plan-vs-impl gaps

18. **[INFO] Platform-Explorer refresh integration deferred.** Plan
    §Caching says *"The Platform Explorer tool window's `Refresh` action
    should invalidate this cache too (follow-up — not blocking the MCP
    tool)."* Verified: the tool window code is unchanged in this PR;
    `arch.list_listeners` is wired to MCP only. Consistent with plan.

19. **[INFO] `@McpDescription` quality is high.** Five-section
    structure (what / when / when-not / returns / examples) intact,
    trim-margin formatting, mentions `arch.list_plugins` as the
    `providedByPluginId` discovery path, calls out the 200-400 listener
    typical count, and the four examples cover the common access
    patterns (topic-by-name, scope-only, plugin-filter, combined). The
    DO-NOT-USE section explicitly names `MessageBus.connect().subscribe`,
    which directly preempts the most likely agent misuse. Excellent
    `@McpDescription` work.

## Research notes

- `IdeaPluginDescriptorImpl` is **Kotlin** in current master
  (`platform/core-impl/src/com/intellij/ide/plugins/IdeaPluginDescriptorImpl.kt`).
  Public properties: `appContainerDescriptor`, `projectContainerDescriptor`,
  `moduleContainerDescriptor`, all `val ContainerDescriptor`. The plan’s
  text says "in current builds it's a separate file in the same package"
  for `ContainerDescriptor` — confirmed; both are Kotlin files. Kotlin
  `val` properties expose a JVM backing field that
  `Class.getDeclaredField("appContainerDescriptor")` finds — so the
  reflective approach works without needing to go through the generated
  getter. The plan text noting "`app` / `project`" as a Java-era legacy
  naming is the right defensive posture; the fallback costs ~1 extra
  reflective miss per plugin per scope, negligible.
- `ListenerDescriptor`: per the JetBrains "Plugin Listeners" doc, the
  XML attributes are `topic`, `class`, `activeInTestMode`,
  `activeInHeadlessMode`. The platform Kotlin class historically mapped
  these to `topicClassName`, `listenerClassName`, `activeInTestMode`,
  `activeInHeadlessMode` (+ optional `os`). The plan’s field-rename
  worry (`topicClassName` → `topicClass` on some 251.x builds) couldn’t
  be confirmed from public sources during this review, but the fallback
  pair `topicClassName | topicClass` is cheap insurance and matches
  the same defensive pattern used elsewhere in `ListenerInspector`.
- Confirmed via web search and JetBrains docs:
  - [Plugin Listeners doc — `topic` / `class` / `activeInTestMode` / `activeInHeadlessMode`](https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html)
  - "Test mode implies headless mode" — matters because the
    `ListenerInfo.activeInTestMode=false, activeInHeadlessMode=true`
    combination is *legal but rare*. The model captures both
    independently, which is the right call.

## References

- IntelliJ master `IdeaPluginDescriptorImpl.kt`:
  https://github.com/JetBrains/intellij-community/blob/master/platform/core-impl/src/com/intellij/ide/plugins/IdeaPluginDescriptorImpl.kt
- IntelliJ Plugin Listeners doc:
  https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html
- Plan: `/home/user/ide-introspector-plugin/docs/plans/arch-list-listeners.md`
- Sibling pattern reused: `core/ServiceInventory.kt` (same
  reflective container-descriptor walk for `<applicationService>`).
- Cache pattern: `core/PluginInventory.kt` (30 s TTL) — `ListenerInspector`
  doubles it to 60 s, which matches the data’s reduced churn.
