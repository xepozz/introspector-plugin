# `ui.list_tool_windows` + `ui.list_dialogs`

## Purpose & motivation

The only `ui.*` entry point today is `ui.get_tree`, which returns a raw Swing
tree. Answering "which tool windows exist?" or "is a modal up?" requires
reconstructing semantic state by hand. These two tools expose it directly:
`ui.list_tool_windows` returns the focused project's tool-window inventory
(id, anchor, visibility, content count, providing plugin); `ui.list_dialogs`
returns the open `JDialog` set with modality and content class. JetBrains'
built-in MCP server ships zero UI tools — exclusive niche.

Success criterion: agents decide whether to focus / capture a tool window or
dialog from one call instead of crawling `ui.get_tree`.

## Tool specifications

### `ui.list_tool_windows`

**Signature:**
```kotlin
@McpTool(name = "ui.list_tool_windows")
@McpDescription(/* verbatim block below */)
suspend fun ui_list_tool_windows(
    @McpDescription("Include tool windows that are currently hidden. Default true.") includeInvisible: Boolean = true,
    @McpDescription("Case-insensitive substring filter on id OR displayName. Null = no filter.") nameContains: String? = null,
): ToolWindowsResponse
```

**`@McpDescription` (verbatim, trim-margin):**
```
|Returns a semantic inventory of every registered tool window in the focused
|project — NOT a Swing component tree. Each entry: id, displayName, anchor
|(LEFT/RIGHT/BOTTOM/TOP), visibility, active/focused flag, split-mode flag,
|type (DOCKED/FLOATING/SLIDING/WINDOWED), iconPath, content-tab count, and the
|pluginId that contributed it (cross-referenced from our `arch.*` inventory via
|the `com.intellij.toolWindow` extension point).
|
|Use this when: you need to know WHICH tool windows exist before focusing,
|capturing, or drilling into one. Typical first call for "open the Database tool
|window" or "what's in the Problems view".
|
|Do NOT use this when: you need the inner Swing tree of a specific tool window —
|use `ui.get_tree` with `rootSelector="tool_window:<id>"`.
|
|Returns: { toolWindows: ToolWindowInfo[], project: string?, warnings: string[] }.
|`iconPath` is a best-effort toString — may be null for procedural icons.
|`providedByPluginId` is null when the window was registered programmatically.
|
|Examples:
|  (no args)                  — every tool window, visible + hidden
|  includeInvisible=false     — only currently shown
|  nameContains="Problems"    — substring filter on id or displayName
```

**Response model (new file `model/ToolWindowInfo.kt`):**
```kotlin
@Serializable
data class ToolWindowsResponse(
    val toolWindows: List<ToolWindowInfo>,
    val project: String? = null,        // Project.name; null when no focused project
    val warnings: List<String> = emptyList(),
)
@Serializable
data class ToolWindowInfo(
    val id: String, val displayName: String,
    val anchor: String,                 // LEFT | RIGHT | BOTTOM | TOP
    val type: String,                   // DOCKED | FLOATING | SLIDING | WINDOWED
    val isVisible: Boolean, val isActive: Boolean,
    val isSplit: Boolean, val isFloating: Boolean,
    val iconPath: String? = null,
    val contentCount: Int,
    val providedByPluginId: String? = null,
)
```

### `ui.list_dialogs`

**Signature:**
```kotlin
@McpTool(name = "ui.list_dialogs")
@McpDescription(/* verbatim block below */)
suspend fun ui_list_dialogs(
    @McpDescription("Include Dialogs that exist but aren't showing (isShowing==false). Default false.") includeInvisible: Boolean = false,
): DialogsResponse
```

**`@McpDescription` (verbatim, trim-margin):**
```
|Lists currently-open `JDialog` / `java.awt.Dialog` windows across the JVM —
|both modal and modeless. Each entry: title, ComponentRegistry id (reusable with
|`ui.get_tree` / `ui.get_properties` / `screenshot.capture`), bounds, modality,
|resizable flag, and the FQN of the dialog's content class (typically a
|`DialogWrapper` subclass for IntelliJ dialogs).
|
|Use this when: you need to know IF a dialog is up before deciding what to do
|next — e.g. before invoking an action, confirm no blocking modal is in the way.
|Complements `ui.get_tree` (returns one dialog's tree) — call `ui.list_dialogs`
|first to discover ids.
|
|Do NOT use this when: you want regular IDE frames (use `ui.find_by_xpath` with
|`//IdeFrameImpl`), popups (`JBPopup` / heavyweight popup menus), or notifications
|(live in tool windows / balloons).
|
|Returns: { dialogs: DialogInfo[], warnings: string[] }. `title` may be null.
|`contentClass` resolves the `DialogWrapper` peer via
|`DialogWrapper.findInstance(component)` when possible, else falls back to the
|dialog's own `getClass().name`.
|
|Examples:
|  (no args)                  — every showing dialog (modal + modeless)
|  includeInvisible=true      — include dialogs that exist but aren't showing
```

**Response model (new file `model/DialogInfo.kt`):**
```kotlin
@Serializable
data class DialogsResponse(
    val dialogs: List<DialogInfo>,
    val warnings: List<String> = emptyList(),
)
@Serializable
data class DialogInfo(
    val id: String,                     // ComponentRegistry id, reusable across ui.*
    val title: String? = null,
    val isModal: Boolean, val isResizable: Boolean, val isShowing: Boolean,
    val bounds: Bounds,                 // existing model/Bounds.kt
    val contentClass: String,           // DialogWrapper FQN if resolvable, else Dialog FQN
)
```

## IntelliJ APIs used

- `com.intellij.openapi.wm.ToolWindowManager.getInstance(project)`,
  `toolWindowIds: Array<String>`, `getToolWindow(id): ToolWindow?`.
- `ToolWindow`: `id`, `stripeTitle`/`title`, `anchor`, `isVisible`, `isActive`,
  `isSplitMode`, `type` (`ToolWindowType`), `icon`, `contentManager.contentCount`
  — all stable public API.
- Focused project: `IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project`,
  fall back to `ProjectManager.getInstance().openProjects.firstOrNull()`.
- Plugin attribution: reuse `core/internal/ExtensionMetadata.kt` (TtlCache-backed)
  — enumerate `com.intellij.toolWindow` EP, key by `id` attribute, value =
  `pluginDescriptor.pluginId.idString`. Per CLAUDE.md pitfalls: use `ep.size()`
  and `ExtensionComponentAdapter.pluginDescriptor`, never `extensionList`.
- Dialogs: `java.awt.Window.getWindows()` filtered to `java.awt.Dialog`;
  `com.intellij.openapi.ui.DialogWrapper.findInstance(Component)` for content class.

## Threading & EDT model

Both tools touch Swing state and **must** wrap logic in `onEdtBlocking { … }`
(`ModalityState.any()` — not blocked by our own exec confirmation).
`ToolWindowManager.getToolWindow(id)` and most `ToolWindow` getters are EDT-only;
`Window.getWindows()` is thread-safe but resolving titles/bounds and
`DialogWrapper.findInstance` (walks client-property hierarchy) requires EDT. No
`ReadAction` — neither tool touches PSI/VFS. EP enumeration for plugin attribution
reuses the thread-safe `ExtensionMetadata` cache off-EDT.

## Timeout strategy

Cheap: tool-window inventory O(N) over `toolWindowIds` (≈15–40); dialog inventory
O(W) over `Window.getWindows()` (≈<10). Both fit easily under the 10 s
`onEdtBlocking` cap. No paging.

## Edge cases

1. **No focused project** → `ToolWindowsResponse(toolWindows=[], project=null,
   warnings=["No focused project; tool windows are project-scoped."])`. Do not throw.
2. **Multi-project IntelliJ** — dialogs are top-level Windows, so a dialog from
   a non-focused project still appears in `ui.list_dialogs`. Intentional: agent
   needs to see ALL blocking modals.
3. **`JDialog` with null title** → emit `title = null`, not `""`. **`DialogWrapper`
   not yet shown (`isShowing=false`)** → listed only when `includeInvisible=true`.
4. **Tool window not yet initialised** — `contentManager.contentCount` can throw
   before lazy init. Wrap per-window collection in `runCatching { … }`; on failure
   emit a `warnings` entry with the id.
5. **Procedural `ToolWindow.icon`** — `toString()` useless; emit `null`. **Splash /
   login windows early in startup** — filter strictly by `is Dialog`.
6. **`com.intellij.toolWindow` EP without `id` attribute** — `providedByPluginId`
   ends up `null` (same fallback as `arch.*`).

## Files to create/modify

| Path | Op | What |
|------|----|------|
| `core/ToolWindowInspector.kt` | Create | EDT collection + plugin-id attribution via `ExtensionMetadata`. |
| `core/DialogInspector.kt` | Create | EDT dialog enumeration + `DialogWrapper.findInstance`. |
| `model/ToolWindowInfo.kt` | Create | `ToolWindowInfo` + `ToolWindowsResponse`. |
| `model/DialogInfo.kt` | Create | `DialogInfo` + `DialogsResponse`. |
| `model/args/UiArgs.kt` | Edit | KDoc only — args are inline primitives. |
| `tools/UiInspectorToolset.kt` | Edit | Add the two `@McpTool` methods delegating to inspectors inside `onEdtBlocking`. |
| `test/.../core/platform/ToolWindowInspectorPlatformTest.kt` | Create | `BasePlatformTestCase` — fixture has Project tool window. |
| `test/.../core/platform/DialogInspectorPlatformTest.kt` | Create | `BasePlatformTestCase` — opens a minimal `DialogWrapper`. |

No new META-INF wiring — both tools live in the always-loaded
`UiInspectorToolset` registered by `mcp-integration.xml`.

## Test plan

Platform tests (`BasePlatformTestCase`):

`ToolWindowInspectorPlatformTest` — (1) fixture project exposes `Project` with
`anchor=="LEFT"`; (2) `nameContains` is case-insensitive (`"project"`/`"PROJECT"`
both match); (3) `includeInvisible=false` excludes hidden windows;
(4) `Project` attributed to plugin `com.intellij`.

`DialogInspectorPlatformTest` — (1) empty list when no dialogs open;
(2) open `JDialog` with known title surfaces with correct `isModal`; (3) minimal
`DialogWrapper` resolves `contentClass` to its FQN, not `JDialog`; (4) `JDialog()`
with no title emits `title = null`.

Toolset wrappers are thin pass-throughs — no separate toolset test.

## Estimated effort

- ~0.4 d `ToolWindowInspector` + plugin-id attribution; ~0.3 d `DialogInspector`
  + `DialogWrapper` resolution; ~0.2 d toolset wiring/models/`@McpDescription`
  polish; ~0.1 d platform tests. **Total: ~1 day combined**, matches the README
  inventory entry.

## Open questions / risks

1. Filter IDE-internal infrastructure dialogs (auto-update notification, splash,
   hidden helpers)? Leaning **no** for v1 — agent should see what the user sees.
   Revisit if noisy.
2. Surface the `Disposable` parent of tool-window content for lifecycle
   debugging? Out of scope for v1 — internal API for marginal value.
3. `ToolWindow.stripeTitle` vs `title` — use `stripeTitle` (user-facing), fall
   back to `id` if blank.
4. Plugin attribution caches via the 60 s `TtlCache`; a freshly installed
   plugin's tool window lags ≤1 min. Acceptable — same as `arch.*`.

## References

- `tools/UiInspectorToolset.kt` — `ui.get_tree` shows the `onEdtBlocking` pattern.
- `core/ComponentTreeWalker.collectToolWindowRoots` — same EDT idiom.
- `core/internal/ExtensionMetadata.kt` — TtlCache-backed EP cache; reuse here.
- JetBrains MCP equivalent: **none** — built-in 2025.2+ MCP server ships zero UI tools.
