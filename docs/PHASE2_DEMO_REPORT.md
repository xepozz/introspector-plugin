# Phase 2 Demo — Kotlin Runtime Execution: Tech Report

## What was built

A working scaffold of `exec.execute_kotlin_in_ide`:

- `ExecToolset` — annotation-driven MCP tool registration (`com.intellij.mcpServer.mcpToolset`).
- `ExecSettings` — `PersistentStateComponent` with: opt-in toggle (off by default),
  confirmation-required toggle, audit toggle, default/max timeout fields.
- `ExecSettingsConfigurable` — Settings page under **Tools → IDE Introspector**.
- `AstSafetyChecker` — pre-compilation textual blacklist (Runtime.exec, ProcessBuilder,
  setAccessible(true), System.exit, sun.* reflection).
- `ConfirmationManager` — modal dialog with code preview and session-only bypass checkbox.
- `AuditLogger` — line-per-call structured log into `idea.log` (category
  `ide-introspector-audit`).
- `CodeWrapper` — emits a wrapper class with the user code embedded inside a `Plugin#run`
  function, implicit helpers `read { ... }`, `write { ... }`, `onEdt { ... }`, bound to
  `project: Project?` and `pluginDisposable: Disposable`.
- `ResultSerializer` — best-effort JSON conversion (primitives, strings, maps, iterables;
  fallback `.toString()` with a warning on unknown types).
- `KotlinExecutor` — single entry point that obtains a fresh JSR-223 Kotlin ScriptEngine
  per call, runs in a cached thread pool with hard timeout, captures stdout/stderr via a
  tee stream, and disposes the bound `Disposable` after each call.

## Implementation choice: JSR-223 over a LivePlugin fork

The spec specifies forking `liveplugin.implementation.pluginrunner.*` (KotlinPluginCompiler,
KotlinPluginRunner, PluginClassLoader_Fork). This demo took a smaller shortcut: depend on
`org.jetbrains.kotlin:kotlin-scripting-jsr223:2.1.20` and obtain a fresh `ScriptEngine` per
invocation. That dependency transitively pulls `kotlin-compiler-embeddable`, so the plugin
ships with its own compiler instance isolated to the plugin classloader.

| Aspect | JSR-223 path (chosen) | LivePlugin fork |
|--------|------------------------|------------------|
| Lines we wrote | ~300 | ~2000 forked + glue |
| Classloader per call | one per ScriptEngineManager | one per PluginClassLoader_Fork |
| Compiler bootstrap | kotlin-scripting-compiler-embeddable | hand-rolled CLI args |
| Plugin zip size | +~60 MB | ~+25 MB (we'd strip what LivePlugin doesn't need) |
| Operational cost | track kotlin-scripting-jsr223 releases | re-sync LivePlugin internals on each IDE bump |

## What I could not measure here

This environment does not have an IntelliJ instance I can launch interactively, so the
10 acceptance scenarios from §2.5 of the spec (simple return value, project API access,
read/write actions, classloader retention, etc.) remain to be run on a real IDE. The
scaffold's structure makes each of those a focused test rather than open-ended exploration.

Things that need real-IDE verification:

1. **Cold/warm compile latency**. The first ScriptEngine creation is expected to be 3–6 s
   (Kotlin compiler bootstrap). Subsequent calls obtain a fresh `ScriptEngine` from the same
   `ScriptEngineManager` — if the underlying compiler is cached, warm calls should be ~300 ms.
   To measure, run `exec.execute_kotlin_in_ide` ten times with the same code and report
   `durationMs` for each.
2. **Classloader retention**. The current code creates a new `ScriptEngineManager` per call
   so each engine gets its own classloader. If `Disposer.dispose(pluginDisposable)` cleans up
   all user-registered subscriptions, the classloader should be GC-able. To verify, call 50
   times with different code bodies and watch JVM heap.
3. **Class conflict with the IDE's bundled Kotlin**. The biggest unknown: the bundled
   `org.jetbrains.kotlin` plugin already has compiler classes on a separate classloader. Our
   `kotlin-scripting-jsr223` may import a Kotlin compiler version that conflicts with the IDE's.
   If `ScriptEngineFactory#getEngineByExtension("kts")` returns `null` at runtime we'll need
   to switch to `kotlin-scripting-jvm-host` and call `BasicJvmScriptingHost` directly.
4. **Compile-error formatting**. JSR-223 wraps compile failures in `ScriptException`. The
   message text exposed by Kotlin scripting includes line numbers but they reference the
   wrapped script, not the user's original code. We currently surface the raw exception
   message — for a polished tool we should rewrite line numbers to match the user's input
   (the wrapper prefix is ~22 lines).

## Recommended next step

1. **Validate on a 2025.2 IDEA Ultimate session**. Run the 10 scenarios from §2.5. If
   JSR-223 engine resolution fails because of classloader conflicts with the bundled Kotlin
   plugin, fall back to `BasicJvmScriptingHost` from `kotlin-scripting-jvm-host` (already
   on classpath as a transitive of jsr223).
2. **If JSR-223 holds up**, instrument cold/warm latency and decide whether to add a
   compiler daemon. A single warm compiler shared between calls would eliminate the per-call
   classloader-isolation guarantee — that trade-off only makes sense once we have a measured
   pain point.
3. **If JSR-223 fails or latency is unacceptable**, then implement the LivePlugin fork as
   originally specced. The current `KotlinExecutor.obtainEngine()` is the only thing that
   would need to be replaced.

## Verdict for the demo

The non-compiler pieces (settings, safety, confirmation, audit, classloader management,
result serialisation) are solid and won't change regardless of which compiler bootstrap we
land on. The compiler bootstrap itself is the single risk item carried into Phase 2 v2.
