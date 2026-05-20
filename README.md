# IDE Introspect MCP

![Build](https://github.com/xepozz/introspector-plugin/workflows/Build/badge.svg)

Exposes the running IntelliJ-based IDE to MCP clients (Claude, Cursor, Codex, ...) as a
set of tools for UI introspection, plugin-architecture exploration, and on-demand
Kotlin code execution. Ships with a **Platform Explorer** tool window for browsing the
live extension-point / plugin graph without an MCP client attached.

## Architecture

Two tiers:

| Tier | Coverage | Latency | Safety |
|------|----------|---------|--------|
| Tier 1: pre-built MCP tools | ~80% of routine introspection | 1–50 ms | whitelist of read-only operations |
| Tier 2: `exec.execute_kotlin_in_ide` | the remaining 20% | 1–5 s (compile) | opt-in + per-call confirmation + textual blacklist |

### Tier 1 — pre-built MCP tools

Registered via the `com.intellij.mcpServer.mcpTool` extension point. All become visible
to MCP clients once the bundled MCP Server plugin is enabled.

| Tool | Purpose |
|------|---------|
| `ui.get_tree` | Full Swing component tree (BFS, capped depth) |
| `ui.find_by_name` | Substring/regex/exact match on name, text, accessibleName, toolTipText |
| `ui.find_by_coordinates` | Deepest visible component at (x, y) + ancestor chain |
| `ui.find_by_xpath` | XPath subset compatible with `intellij-ui-test-robot` |
| `ui.get_properties` | UI-Inspector-style property bag for a component id |
| `screenshot.capture` | Component / active frame / all frames / virtual desktop |
| `screenshot.crop` | Active frame screenshot + crop region |
| `arch.list_extension_points` | Every EP live from this IDE instance |
| `arch.list_extensions_for_ep` | Every extension contributed to a given EP |
| `arch.list_plugins` | Installed plugins with metadata |
| `arch.get_plugin_details` | Declared EPs + registered extensions for a plugin |
| `arch.find_extenders_of` | Reverse search: who plugs into this EP / interface? |

Component ids returned by `ui.*` tools are stable for the duration of the IDE session and
can be passed back into `ui.get_properties` / `screenshot.capture`.

### Tier 1 — Platform Explorer tool window

Right-side tool window with three view modes:

1. **By Plugin** — each plugin with declared EPs and registered extensions.
2. **By Extension Point** — each EP with the list of registered implementations.
3. **By Plugin Dependencies** — declared `<depends>` graph.

Features: SpeedSearch, live filter input (200 ms debounce), HTML details panel,
right-click "Copy plugin id / EP name / class name", refresh button.

### Tier 2 — `exec.execute_kotlin_in_ide` (Phase 2 demo)

Compiles and executes arbitrary Kotlin in the IDE JVM. **Disabled by default**;
toggle in Settings → Tools → IDE Introspect MCP.

User code is wrapped with three implicit helpers (`read`, `write`, `onEdt`) and runs
inside an auto-disposed `Disposable` scope so subscriptions are cleaned up between calls.

**Security**:

1. Off by default (`enabled = false` in `ExecSettings`).
2. Per-call confirmation dialog inside the IDE (with session-only bypass).
3. Textual blacklist: `Runtime.exec`, `ProcessBuilder`, `setAccessible(true)`,
   `System.exit`, `Class.forName("sun.*")`.
4. Audit log to `idea.log` (category `ide-introspect-mcp-audit`).
5. Hard execution timeout (default 30 s, capped at the configured `maxTimeoutMs`).

**Demo implementation note**: the spec calls for forking LivePlugin's compiler bootstrap.
This demo takes a smaller shortcut and uses `kotlin-scripting-jsr223` (which internally
wraps `kotlin-compiler-embeddable`). This keeps the diff small and gives us a fresh
classloader per call out of the box, but bundles ~50 MB of compiler classes into the
plugin zip. A future iteration should either:

- Switch to the LivePlugin fork once measured cold-start latency justifies the operational
  cost of pinning Kotlin compiler versions, or
- Reuse a single compiler daemon across calls to cut compilation overhead.

## Manual verification

After running `./gradlew runIde`:

1. **Tool window**: open the *Platform Explorer* tool window on the right. Switch
   between view modes; type in the filter to narrow the tree.
2. **MCP tools (with MCP Inspector or Claude Desktop)**:
   - `arch.list_extension_points` — should return ≥ 1000 EPs on a vanilla IDEA.
   - `ui.get_tree` with default args — should return ≥ 50 nodes.
   - `ui.find_by_xpath` with `//div[@class='ActionButton' and @text='Run']` — should find
     the Run button on the main toolbar.
   - `arch.find_extenders_of` with target `com.intellij.toolWindow` — should list every
     `ToolWindowFactory` implementation.
3. **Kotlin execution**: enable in Settings, then call `exec.execute_kotlin_in_ide` with
   code like `1 + 1` or `project?.name`. A confirmation dialog should pop up; on accept,
   the result should round-trip as JSON.

## Project layout

```
src/main/kotlin/com/github/xepozz/introspectorplugin/
├── core/         — ComponentRegistry, ComponentTreeWalker, PluginInventory, ExtensionPointInspector, XPathMatcher, ScreenshotCapture
├── model/        — Serializable data classes (ComponentInfo, PluginInfo, …) + tool args
├── tools/
│   ├── ui/       — get_tree, find_by_*, get_properties
│   ├── screenshot/ — capture, crop
│   ├── arch/     — list_extension_points, list_extensions_for_ep, list_plugins, get_plugin_details, find_extenders_of
│   └── exec/     — execute_kotlin_in_ide
├── toolwindow/   — Platform Explorer (panel, tree model, cell renderer, details panel)
├── exec/         — Settings, ConfirmationManager, AstSafetyChecker, AuditLogger, KotlinExecutor, ResultSerializer, CodeWrapper
└── util/         — EdtHelpers, ImageEncoding
```

## Build

```bash
./gradlew buildPlugin
# produces build/distributions/ide-introspect-mcp-<version>.zip
```
