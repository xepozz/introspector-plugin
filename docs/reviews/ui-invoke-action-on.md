# Review: ui.invoke_action_on

Branch: `claude/project-features-analysis-odEwP` @ `c2d810d` (review HEAD)
Reviewer scope: the new opt-in privileged-WRITE tool — invoker, settings,
confirmation manager, blocklist, audit overload, configurable section,
toolset wrapper, plugin.xml registration, tests.

## Verdict
Needs changes before merge. The structure (off-by-default service, two-stage
modal, blocklist double-confirm, audit log overload, 10 s EDT cap, headless
invoker) is right and mirrors `exec.*` faithfully — but a logic bug in
`UiActionConfirmationManager.confirm` lets an MCP agent silently bypass the
default confirmation by passing `requireConfirmation=false`, which inverts the
flag's documented "force prompt" semantics and unstates the user's global
setting. Fix that plus the `action.update(event)` short-cut (it drops the
dumb-mode / SlowOperations / document-commit guards that
`ActionUtil.lastUpdateAndCheckDumb` performs) and this is mergeable.

## Summary
- Files added/edited match the plan list 1:1. Tool sits inside `UiInspectorToolset`
  per the plan's "no new META-INF shim" decision (`tools/UiInspectorToolset.kt:344-521`).
- Off-by-default opt-in is correct (`UiActionSettings.kt:26`: `enabled = false`).
- Two-stage confirmation dialog with separate session bypass exists
  (`UiActionConfirmationManager.kt:31,77,93`) — but stage-1 gating logic is broken
  (Finding 1).
- Blocklist (`UiActionBlocklist.kt`) has both default-id and wildcard paths,
  case-insensitive globs, and a user-extendable list. Solid.
- Audit log overload is correct, prefixed `[ui.invoke_action_on]`, gated on
  `UiActionSettings.auditEnabled` independently of exec.
- Hard 10 s timeout is wired correctly via `onEdtBlocking(DEFAULT_EDT_TIMEOUT_MS)`.
- Plugin XML registers the new `applicationService`; configurable extends the
  existing one (correct per plan).
- Tests cover the pure-Kotlin surface (blocklist, truncation, formatters) plus
  a respectable platform suite around `UiActionInvoker.invoke` — but the
  confirmation manager itself has zero tests, which is how Finding 1 slipped in.

## Findings (numbered, severity-tagged)

1. **[CRITICAL] `UiActionConfirmationManager.kt:60-64` — `requireConfirmation`
   semantics are inverted; an agent can skip the prompt by passing `false`.**
   ```kotlin
   val stage1Needed = requireConfirmation && settings.requireConfirmation && !sessionBypass
   ```
   Plan + `@McpDescription` (`UiInspectorToolset.kt:415`,
   `docs/plans/ui-invoke-action-on.md:111`) define `requireConfirmation=true`
   as a **force-on** override ("forces a confirmation prompt even when the
   session bypass is active"). Impl treats it as **force-off**: any call
   with `requireConfirmation=false` skips stage 1 even when the user's
   global `settings.requireConfirmation=true`. A malicious or careless MCP
   client just passes `requireConfirmation=false` and the per-call gate is
   gone. The blocklist double-prompt still fires for `*Delete*`/`*Force*`/
   `*Reset*`, but every non-blocklisted action (Build, RunAnything,
   CheckinProject, plugin-named "Sync", "Apply") fires with no user gate.
   Mirror exec's `ConfirmationManager.kt:25-26`:
   ```kotlin
   val stage1Needed = requireConfirmation || (settings.requireConfirmation && !sessionBypass)
   ```
   Confirmation manager has zero direct tests — Finding 9 is the same root
   cause (test discipline skipped this surface entirely).

2. **[HIGH] `UiActionInvoker.kt:100-102` — `action.update(event)` short-cut
   drops dumb-mode / SlowOperations / document-commit guards.** The fixer
   note ("`lastUpdateAndCheckDumb` no longer respects `isEnabled=false`") is
   the inverse of what the method actually does. `ActionUtil
   .lastUpdateAndCheckDumb(action, event, true)` in 252.x:
   (a) commits pending documents via `PerformWithDocumentsCommitted`;
   (b) wraps `update()` in `SlowOperations.allowSlowOperations(...)` and
   maps `IndexNotReadyException` to "disabled";
   (c) honours the `WOULD_BE_ENABLED_IF_NOT_DUMB_MODE` client property so
   a real-but-disabled-in-dumb-mode action is reported correctly;
   (d) with `visibilityMatters=true` also gates on `presentation.isVisible`.
   None of those happen with bare `action.update(event)`. Agents will see
   correctly-disabled actions mid-indexing and mis-target. Revert to
   `lastUpdateAndCheckDumb(action, event, true)` (still `@JvmStatic`,
   non-deprecated). Better yet, replace the entire build-event /
   update / perform block with `ActionUtil.invokeAction(action, component,
   ActionPlaces.UNKNOWN, null, null)` — one call, all guards intact,
   listener fan-out included. The unit seam for "override-after-update"
   is the only reason to keep the manual split.

3. **[HIGH] `UiActionConfirmationManager.kt:165-172` — `JBLabel(actionId)`
   et al. render HTML when the string starts with `<html>`.** Standard
   `JLabel` auto-detect. Five agent-/plugin-controlled strings render
   unescaped: `actionId`, `actionText`, `pluginOwner`, `componentId`,
   `describeComponent(component)`. An agent calling
   `actionId="<html><b>Trusted</b> Build"` produces a confirmation dialog
   whose action-id label reads "**Trusted** Build" — exactly the
   social-engineering vector a confirmation prompt is supposed to prevent.
   Fix: wrap each value in `XmlStringUtil.escapeString(...)` or render via
   `JTextField(value).apply { isEditable=false; border=null }` (JTextField
   doesn't parse HTML, and the user can copy-paste for verification).

4. **[HIGH] `UiInspectorToolset.kt:418-427` — `enabled=false` and blank-
   `actionId` paths bypass the audit log.** Plan
   (`docs/plans/ui-invoke-action-on.md:79`) requires every call recorded.
   An attacker probing whether the tool is enabled (or a confused agent
   spamming) leaves no trace. The exec twin has the same gap
   (`ExecToolset.kt:113-117`); fix the pattern here.

5. **[MEDIUM] `UiActionSettings.kt:29` — `blocklistedActionIds = mutableListOf()`
   default reads like "the blocklist is empty by default".** Implementation
   is correct (defaults live in `UiActionBlocklist.DEFAULT_*`, this is the
   user-extra list), but the name matches the plan's literal
   `blocklistedActionIds: List<String> = DEFAULT_BLOCKLIST`. Rename to
   `userBlocklistedActionIds` and add a one-line KDoc.

6. **[MEDIUM] `UiInspectorToolset.kt:493-505` — `TimeoutException` always
   reports `executed=false`.** Plan edge case 8 ("the action call has not
   happened") only holds when the EDT never picked up the job; if
   `actionPerformed()` ran but exceeded 10 s, side effects already
   landed. Carry a `volatile var performInvoked = false` inside
   `UiActionInvoker.invoke` and surface it in `Result`; tag the audit
   entry `edt-timeout-perform-attempted` for grep-ability.

7. **[MEDIUM] `UiActionConfirmationManager.kt:32` — process-wide
   `sessionBypass` with no reset hook.** Once the user clicks "Allow for
   session" for one MCP client, every later MCP connection (including a
   different one) inherits the bypass for the IDE process lifetime. Add a
   "Reset session bypass" link in `ExecSettingsConfigurable`, or invalidate
   on project close. Same shape as exec — fix both together.

8. **[MEDIUM] `UiInspectorToolset.kt:460` — `currentCoroutineContext().projectOrNull`
   may be `null`; dialog parent becomes null silently.** `DialogWrapper(null, true)`
   falls back to the active frame but loses parenting in headless/unit-test
   contexts. Fall back to `IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project`
   (same pattern called out in the ui-semantic-listing review).

9. **[MEDIUM] `UiActionInvokerPlatformTest.kt:55-57` — setUp sets
   `enabled=false`, so no test ever reaches the toolset entry point.**
   The KDoc claims this is to skip the dialog, but `ui_invoke_action_on`
   throws on `enabled=false` first thing — the toolset wrapper (the
   actual security surface) is untested. Flip to `enabled=true,
   requireConfirmation=false` and add one end-to-end test per security
   branch (Findings 1, 3, 4 above). Split `UiActionConfirmationManager`
   behind an interface for the dialog test seam.

10. **[LOW] `UiActionInvoker.kt:64-71` — `isShowing` check runs AFTER the
    user has clicked through the confirmation dialog.** Toolset path is
    lookup → blocklist → confirmation → onEdtBlocking → `invoke()` →
    isShowing. Move the check before confirmation to avoid asking the user
    to authorise a no-op.

11. **[LOW] `UiActionConfirmationManager.kt:177` — `bypass.addActionListener`
    flips a field that's read after `dialog.show()`. Works (same EDT
    pump) but the exec twin reads `bypass.isSelected` directly in the OK
    callback — cleaner idiom for a security file.

12. **[LOW] `UiActionBlocklist.kt:35-39` — `*Force*`/`*Delete*`/`*Reset*`
    over-match.** Catches `RefreshAllReset`, `UndeleteRevision`,
    `ResetWindowLayout`, etc. Acceptable for v1 (user-borne cost), but
    tighten with separator anchors (`*.Force.*`, `Force_*`) or extend the
    match to `action.javaClass.simpleName` in v2.

13. **[LOW] `UiInspectorToolset.kt:528-530` — `PluginManagerCore.getPluginByClassName`
    returns `com.intellij` for bundled / EmptyAction-backed ids, not the
    plugin that declared the id in `Actions.xml`.** Use the
    `com.intellij.action` EP lookup pattern via `core/internal/ExtensionMetadata.kt`
    so the dialog tells the user which third-party plugin contributed the
    action id.

14. **[LOW] `AuditLogger.kt:43` — `actionId`/`componentId`/`componentClass`
    are not length-capped.** A pathological 50 000-char `actionId` bloats
    the log line. Cap each at 200 chars, same as the error field.

15. **[LOW] `plugin.xml:46-47` + `ExecSettingsConfigurable.kt:58` —
    discoverability OK (extends existing configurable per plan), but the
    section header "Tier 2: UI action invocation" sits below the Kotlin
    section; a user scanning Settings may miss it. Cosmetic, but consider
    making the UI-action section first since it's likely the more common
    first opt-in.

## Plan-vs-implementation gaps

| Plan asked for | Implementation | Action |
|---|---|---|
| `requireConfirmation=true` forces stage-1 prompt even when session bypass active | `requireConfirmation=false` silently SKIPS the prompt even when global setting requires it | Finding 1 — CRITICAL |
| `ActionUtil.lastUpdateAndCheckDumb(action, event, true)` for the update check | Replaced with bare `action.update(event)` per fixer note | Finding 2 |
| Confirmation manager has separate per-stage session bypass + UI mismatches | Implemented per plan, layout matches | OK |
| Two-stage prompt for blocklisted ids — no bypass for stage 2 | Implemented per plan, `allowSessionBypass=false` on stage 2 | OK |
| Hard 10 s timeout via `onEdtBlocking` | Implemented (`DEFAULT_EDT_TIMEOUT_MS` passed explicitly per plan) | OK |
| Every call audited under `ide-introspector-audit` | Implemented BUT skips audit on `enabled=false` and blank-actionId paths | Finding 3 |
| `presentationText` applied AFTER `update()` runs | Implemented, platform-tested | OK |
| User-editable blocklist | Field exists (empty list), no Configurable editor (plan calls v1 acceptable) | Finding 5 — naming |
| Tests: unit + platform | Both exist; platform tests cover the invoker, not the confirmation manager | Finding 9 |

## Research notes (URLs)

- `ActionUtil.lastUpdateAndCheckDumb` source confirms it commits documents,
  wraps in `SlowOperations`, converts `IndexNotReadyException`, and tracks
  the `WOULD_BE_ENABLED_IF_NOT_DUMB_MODE` client property — none of which
  bare `update()` does. Source:
  https://github.com/JetBrains/intellij-community/blob/idea/243.22562.145/platform/platform-api/src/com/intellij/openapi/actionSystem/ex/ActionUtil.kt
- API doc list of canonical invocation paths:
  https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/actionSystem/ex/ActionUtil.html
  — confirms `invokeAction(action, component, place, inputEvent, onDone)` as the
  one-call entry; we already have a `Component` in hand.
- `AnActionEvent.createFromAnAction(action, null, place, dataContext)` is
  deprecated in master in favour of
  `AnActionEvent.createEvent(dataContext, presentation, place, ActionUiKind.NONE, null)`
  but still works (and is the right call) in 252.x — keep as-is, revisit when
  IDEA 2026.x lands.
  https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/openapi/actionSystem/AnActionEvent.java
- `JBLabel` HTML-rendering behaviour is the standard `JLabel` HTML auto-detect;
  selectable variant via `setCopyable(true)`. Mitigation references:
  https://marcelkliemannel.com/articles/2021/powerful-ui-texts-with-the-html-capable-jblabel/
- JetBrains' bundled MCP server's `ExecuteActionByIdTool` source is no longer
  publicly accessible (the upstream plugin
  https://github.com/JetBrains/mcp-server-plugin was deprecated; functionality
  moved into the bundled `com.intellij.mcpServer` module in 2025.2). Their
  implementation took the focused-frame `DataContext`; our differentiator
  (caller-specified component) remains valid.

## Test coverage assessment

Unit tests (`UiActionInvokerTest.kt`, 11 test methods) cover the blocklist
matcher (exact id, wildcard, case-insensitivity, user extras), presentation
truncation, and error-message formatters. All correctness-only — no
threading, no security path, no dialog interaction. Solid for what they
target.

Platform tests (`UiActionInvokerPlatformTest.kt`, 6 test methods) cover the
core invoker happy path, hidden component, disabled action, override-after-
update ordering, override truncation, and throwing action. Good headless
coverage of `UiActionInvoker.invoke`. Gaps:

- Zero tests for `UiActionConfirmationManager.confirm` — Finding 1 would
  have failed instantly with even a single test asserting
  `requireConfirmation=true` forces a prompt past `sessionBypass=true`.
- Zero tests for the toolset wrapper's `enabled=false` short-circuit, blank
  `actionId` rejection, action-not-found audit, component-detached audit, or
  EDT-timeout path — all the security-critical branches.
- Zero tests for blocklist double-prompt actually triggering against a
  known dangerous id.
- The platform suite sets `enabled=false` and the toolset's first check
  throws if `enabled=false`, so the suite cannot test the toolset entry
  point as-is. Flip the comment + setting and add a single end-to-end test
  that uses a `Decision`-overriding fake `UiActionConfirmationManager`
  (split the manager behind an interface for the test seam).

Recommended additions before merge: one test per Finding 1, 3, 4, 10.

## Cross-cutting suggestions

- The fixer's commit message (`733906d`) and the in-source comment block at
  `UiActionInvoker.kt:96-99` blame `lastUpdateAndCheckDumb` for not respecting
  `isEnabled=false`. Research disagrees — `lastUpdateAndCheckDumb` is *more*
  conservative, not less. Replace the comment and revert the API choice
  (Finding 2). Even if a specific 252 build genuinely had this regression,
  rewriting the implementation around a hard fork is a bigger commitment
  than absorbing one version-specific shim.
- The `Decision` enum (`UiActionConfirmationManager.Decision`) is good — it
  cleanly distinguishes user-rejected from blocklist-rejected without
  string-comparing the audit outcome. Same pattern could be lifted into a
  shared `SecurityConfirmation` base when the third opt-in tool appears
  (the plan flags this).
- `UiInspectorToolset.ui_invoke_action_on` is now ~115 LOC of orchestration
  with five distinct McpExpectedError sites and five audit-log sites. Extract
  a `UiActionDispatcher` (or fold more into `UiActionInvoker`) so the toolset
  method stays a thin wrapper. Cuts review-time complexity for the next opt-in
  feature.
- `@Service(Service.Level.APP)` on `UiActionSettings` is correct. The
  `data class State(...)` with mutable defaults serialises fine through
  `XmlSerializer`; no manual converter needed.
- KDoc on `UiActionInvoker.invoke` says "Must be called on the EDT" — add
  `ApplicationManager.getApplication().assertIsDispatchThread()` as the first
  line so a future caller who skips `onEdtBlocking` gets a hard fail in
  development (the same pattern recommended in the ui-semantic-listing
  review).

## References

- `src/main/kotlin/com/github/xepozz/ide/introspector/tools/UiInspectorToolset.kt:344-521`
  — toolset wrapper, audit sites, error-mapping (Findings 3, 8).
- `src/main/kotlin/com/github/xepozz/ide/introspector/core/UiActionInvoker.kt`
  — headless invoker (Findings 2, 6, 10).
- `src/main/kotlin/com/github/xepozz/ide/introspector/exec/UiActionConfirmationManager.kt:60-64,165-172`
  — confirmation logic + HTML-injection sites (Findings 1, 4, 7, 11).
- `src/main/kotlin/com/github/xepozz/ide/introspector/exec/UiActionSettings.kt:29`
  — naming + default (Finding 5).
- `src/main/kotlin/com/github/xepozz/ide/introspector/exec/UiActionBlocklist.kt:35-39`
  — wildcard tightening (Finding 12).
- `src/main/kotlin/com/github/xepozz/ide/introspector/exec/AuditLogger.kt:28-44`
  — overload (Finding 14).
- `src/main/resources/META-INF/plugin.xml:46-47`
  — service registration (Finding 15).
- `src/test/kotlin/com/github/xepozz/ide/introspector/core/UiActionInvokerTest.kt`
  — unit coverage.
- `src/test/kotlin/com/github/xepozz/ide/introspector/core/platform/UiActionInvokerPlatformTest.kt:55-57`
  — platform setUp note (Finding 9).
- `docs/plans/ui-invoke-action-on.md:111,135,202-213` — contract this PR claims
  to implement.
- `src/main/kotlin/com/github/xepozz/ide/introspector/exec/ConfirmationManager.kt:24-26`
  — the correct boolean pattern to mirror (Finding 1).
