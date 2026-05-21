<!-- Project memory for Claude Code. Keep this file under ~200 lines and focused on
     facts that apply every session. Single-feature notes go into path-scoped rules
     under .claude/rules/ if/when we add them. -->

# IDE Introspector

IntelliJ IDE plugin that exposes the running IDE to MCP clients (Claude, Cursor, …) as
~13 tools across four groups: `ui.*` (Swing introspection), `screenshot.*`,
`arch.*` (plugin/extension-point inventory), `exec.*` (opt-in Kotlin runtime execution).
Ships with a "Platform Explorer" tool window for the same data without an MCP client.

See [docs/CODE_QUALITY.md](docs/CODE_QUALITY.md) for the full Kotlin / IntelliJ-plugin /
testing criteria. The Hard rules below take precedence; the guide expands on the rest.

## Build commands

- `./gradlew build` — full build (compileKotlin → KSP → verifyPlugin → tests). Must pass
  before any push.
- `./gradlew buildPlugin` — produces the distributable zip at
  `build/distributions/ide-introspector-<version>.zip`. Hooks the doc generator too.
- `./gradlew runIde` — launches a sandbox IDE for manual testing.
- `./gradlew compileKotlin` — fastest feedback; also runs the KSP doc-processor so
  `docs/MCP_TOOLS.md` refreshes.

## Project layout

```
introspector-plugin/
├── build.gradle.kts                # main plugin module
├── settings.gradle.kts             # includes the doc-processor subproject
├── doc-processor/                  # KSP processor that renders docs/MCP_TOOLS.md
├── docs/                           # human-facing docs (MCP_TOOLS.md is generated)
└── src/main/
    ├── kotlin/com/github/xepozz/introspector/
    │   ├── core/                   # ComponentRegistry / Walker / Serializer,
    │   │                           #   XPathMatcher, PluginInventory,
    │   │                           #   ExtensionPointInspector, ScreenshotCapture
    │   ├── model/                  # @Serializable response types + tool args
    │   ├── tools/                  # one McpToolset class per tool group
    │   │                           #   UiInspectorToolset / ScreenshotToolset /
    │   │                           #   ArchitectureToolset / ExecToolset
    │   ├── toolwindow/             # Platform Explorer tool window
    │   ├── exec/                   # Phase 2 — opt-in Kotlin runtime execution
    │   └── util/                   # EdtHelpers, ImageEncoding
    └── resources/META-INF/
        ├── plugin.xml              # base plugin + tool window + settings
        ├── mcp-integration.xml     # loaded only if com.intellij.mcpServer is present
        └── kotlin-exec.xml         # loaded only if org.jetbrains.kotlin is also present
```

## Hard rules

### Timeouts: 10 s cap, no exceptions

Anything async/blocking in this codebase **must** time out at 10 seconds or less.

- `onEdtBlocking` and any other EDT-bouncing helper (`DEFAULT_EDT_TIMEOUT_MS = 10_000L`)
- `ExecSettings.defaultTimeoutMs` and `ExecSettings.maxTimeoutMs` (both capped at 10_000)
- `ExecuteKotlinArgs.timeoutMs` default and the `ExecToolset` parameter
- Anything new — `withTimeoutOrNull`, `Future.get`, network calls, locks, latches

If a tool genuinely needs more time, **make it cheaper** (narrower scope, fewer features,
async streaming) rather than raising the timeout. Long timeouts in this plugin freeze the
IDE and break MCP-client UX.

### MCP API target

Build against `com.intellij.mcpServer:252.28238.29` — the **new** annotation-driven API
(`McpToolset` + `@McpTool` + `@McpDescription`), NOT the older `AbstractMcpTool` /
`org.jetbrains.ide.mcp.Response` model. The JetBrains/mcp-server-plugin open-source repo
is stale relative to what ships in the IDE.

### Serialization classloader policy

`kotlinx-serialization-json` is `compileOnly` (not `implementation`). The IDE provides
the runtime copy. If you change this to `implementation`, our `@Serializable` data
classes fail in `CallableBridge.serializerOrNull` because the IDE sees two `KSerializer`
classes from two classloaders. Symptom: `"Result type X is not serializable"`.

### Pre-built tools over exec

`exec.execute_kotlin_in_ide` is the escape hatch. Use `ui.*` / `arch.*` / `screenshot.*`
first; only fall back to exec when no pre-built tool covers the need. Exec is opt-in
(off by default in `ExecSettings`), every call shows a modal confirmation by default,
and a textual blacklist rejects `Runtime.exec` / `ProcessBuilder` /
`setAccessible(true)` / `System.exit` / `Class.forName("sun.*")` before compilation.

## Conventions

### Tool descriptions

Tool `@McpDescription` strings follow the structure (mirrors Anthropic's
"define-tools" guidance):

1. **What it does** (one-line, present tense, action + scope)
2. **Use this when**: concrete intents
3. **Do NOT use this when**: alternative tools or out-of-scope cases
4. **Returns**: shape of the JSON, key fields named
5. **Examples** (for non-trivial tools) — runnable invocations to copy

Use Kotlin trim-margin strings (`""" |line… """`) — the MCP framework's reflection bridge
calls `trimMargin` automatically.

### Threading

MCP tool methods are `suspend` and run on a background ktor coroutine, NOT the EDT. Any
Swing access must go through `onEdtBlocking { … }` (which uses `ModalityState.any()` so
it isn't held up by an open modal dialog — e.g. the exec confirmation).

`ExtensionPoint` enumeration is thread-safe — `arch.*` tools don't need EDT bouncing.

### EDT collection is expensive — default to cheap

`ui.get_tree` defaults to `maxDepth=12`, `includeProperties=false`. Property collection
walks reflection on every node and saturates the EDT past ~5 000 nodes. Callers who need
properties should call `ui.get_properties` for one specific component id.

### Adding a new MCP tool

1. Add a `suspend fun` to the matching `McpToolset` class under `src/main/kotlin/.../tools/`.
2. Annotate with `@McpTool(name = "group.snake_case_name")` and `@McpDescription("…")`.
3. Annotate every parameter with `@McpDescription("…")`.
4. Wrap any Swing access in `onEdtBlocking { … }`.
5. Return a `@Serializable` data class — `model/` is the right home.
6. `./gradlew build` regenerates `docs/MCP_TOOLS.md` via the KSP processor automatically.

## Doc generation

`docs/MCP_TOOLS.md` is auto-generated from `@McpTool` / `@McpDescription` annotations by
a KSP processor in `doc-processor/`. The processor hooks into `compileKotlin`, so every
`./gradlew build` / `buildPlugin` refreshes it. Do **not** edit the markdown by hand —
edit the source annotations.

## Common pitfalls

- **`./gradlew build` fails to download `asm-all-9.6.1.jar`**: stale cloudfront cert from
  the IntelliJ Platform Gradle Plugin. Manually drop the jar into
  `~/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.deps/asm-all/9.6.1/<sha1>/`
  rather than fighting the retry logic.
- **`ep.extensionList.size` for counting extensions**: don't. It instantiates every
  extension and surfaces latent registration bugs in unrelated plugins (e.g.
  `BuildManager$BuildManagerStartupActivity` in some `com.intellij.java` builds throws
  on load). Use `ep.size()` — adapter count, no instantiation.
- **`ExtensionComponentAdapter.pluginDescriptor` is a public field, not a getter** —
  same with `XmlExtensionAdapter.extensionElement` (private field, get via
  `readField()`). `getPluginDescriptor()` reflection returns null and every contributed
  extension ends up attributed to `"unknown"`.
- **`XmlElement.extensionElement` is nulled out after the extension is instantiated**
  (memory optimization). For already-loaded extensions (services, tool windows on
  startup) the XML element is gone — fall back to harvesting public/JvmField properties
  off the bean instance.
- **Modal dialogs and `invokeLater`**: `ModalityState.NON_MODAL` (the default) stalls
  behind ANY open modal — including our own exec-confirmation dialog. Always use
  `ModalityState.any()` for tool-handler EDT bounces.
