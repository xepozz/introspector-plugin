# `exec.compile_check` — Kotlin snippet compile-only validation

## Purpose & motivation

A "cheap lint" sibling of `exec.execute_kotlin_in_ide`: run a snippet through
the kotlin-scripting-jsr223 compiler we already host and return all
diagnostics (errors, warnings, info) with line/column positions — **without
executing anything**. Today an agent that wants to validate generated code
before pushing it to the user has to either call `exec.execute_kotlin_in_ide`
(side effects, modal confirmation, opt-in gate) or punt to apply-then-fix.
JetBrains' built-in MCP server has `build_project` (full project rebuild) but
NO snippet-level Kotlin compile — pure gap.

Because nothing runs, the tool is **default-on**: no `ExecSettings.enabled`
gate, no `AstSafetyChecker`, no `ConfirmationManager`, no `AuditLogger`. The
only precondition is `kotlin-scripting-jsr223` on the classpath, already
guaranteed by `META-INF/kotlin-exec.xml` (the same shim that loads
`ExecToolset` at all).

**Success criterion:** agent calls `exec.compile_check` in <1 s warm and
decides whether to iterate or surface; with `wrap=true` the compile path
reuses `CodeWrapper.wrap` verbatim, so "compiles here" implies "compiles
under `exec.execute_kotlin_in_ide`".

## Tool specification

### `exec.compile_check`

**Signature:**
```kotlin
@McpTool(name = "exec.compile_check")
@McpDescription(""" |… verbatim block below … """)
suspend fun exec_compile_check(
    @McpDescription("Kotlin source to compile. Same shape as exec.execute_kotlin_in_ide.code when wrap=true.")
    code: String,
    @McpDescription("If true (default), wrap in the SAME Plugin-class template execute_kotlin_in_ide uses, so 'project', 'pluginDisposable', and read/write/onEdt helpers resolve. If false, compile as a raw top-level .kts script.")
    wrap: Boolean = true,
): CompileCheckResponse
```

**`@McpDescription` draft** (verbatim, trim-margin):

```
|Compiles a Kotlin snippet in-process and returns every compiler diagnostic
|(errors, warnings, info) with line/column positions. NOTHING IS EXECUTED — no
|side effects, no confirmation dialog, no audit log entry.
|
|Use this when:
| - You generated a Kotlin snippet and want to verify it compiles BEFORE
|   asking the user to apply it (or before invoking exec.execute_kotlin_in_ide).
| - You want a fast syntax / type-check on a snippet without spinning up a
|   full Gradle build (use JetBrains' build_project for whole-project rebuilds).
| - You want to iterate "fix the next compile error" without running anything.
|
|Do NOT use this when:
| - You actually want the side effects — use exec.execute_kotlin_in_ide.
| - You want to compile a multi-file change against project sources — JSR-223
|   is single-script only; use the IDE's full build via JetBrains' build_project.
| - You want lint/style checks beyond the compiler's own diagnostics — this
|   tool only surfaces what the Kotlin compiler itself reports.
|
|Always-on: NOT gated by the 'Allow Kotlin code execution' setting and does
|NOT trigger the modal confirmation dialog. Compile is read-only. It does
|require the org.jetbrains.kotlin plugin (same prerequisite as
|exec.execute_kotlin_in_ide); on IDEs without it, the tool isn't registered.
|
|WRAPPING (wrap=true, default): the snippet is embedded in the same template
|execute_kotlin_in_ide uses, so implicit bindings resolve at compile time:
| - project: Project?
| - pluginDisposable: Disposable
| - read { } / write { } / onEdt { } helpers
|This guarantees that "compiles here" implies "compiles under exec.execute_*".
|wrap=false: the input is compiled as a raw top-level .kts script — use this
|to lint a self-contained file that already has its own imports.
|
|Returns: {
|  ok: Boolean,                          // true iff zero ERROR/FATAL diagnostics
|  diagnostics: [{
|    severity: "FATAL" | "ERROR" | "WARNING" | "INFO" | "DEBUG",
|    line: Int?, column: Int?,           // 1-based; null if no position
|    file: String?,                      // synthetic name of the wrapped script
|    message: String,
|    factoryId: String?                  // e.g. "UNRESOLVED_REFERENCE"; may be null
|  }],
|  warnings: [String],                   // tool-side warnings (e.g. "timed out")
|  durationMs: Long
|}
|
|Examples:
|  // Verify a generated snippet compiles before running it:
|  exec.compile_check code="""
|    read { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project!!)
|      .openFiles.map { it.path } }
|  """
|
|  // Lint a self-contained file with its own imports:
|  exec.compile_check code="import kotlin.math.*; val x: Int = sqrt(4.0).toInt()" wrap=false
```

**Args:**
- `code: String` — required, the snippet. No tool-side size limit.
- `wrap: Boolean = true` — if `true`, run input through `CodeWrapper.wrap`
  before compiling so it lints under the executor's symbol table.

**Response model** (new file `model/CompileCheckInfo.kt`; args alongside
existing `model/args/ExecArgs.kt`):

```kotlin
@Serializable data class CompileCheckResponse(
    val ok: Boolean,
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    val warnings: List<String> = emptyList(),
    val durationMs: Long,
)

@Serializable data class CompileDiagnostic(
    val severity: String,        // FATAL | ERROR | WARNING | INFO | DEBUG
    val line: Int? = null,
    val column: Int? = null,
    val file: String? = null,
    val message: String,
    val factoryId: String? = null,
)

@Serializable data class CompileCheckArgs(val code: String, val wrap: Boolean = true)
```

## Kotlin scripting APIs used

Primary path (rich diagnostics):
- `kotlin.script.experimental.jvmhost.BasicJvmScriptingHost` — entry point.
- `host.compiler` (a `JvmScriptCompiler`) — `compile(source, configuration)`
  (suspend) returns `ResultWithDiagnostics<CompiledScript>`. Pull `.reports`
  regardless of `Success`/`Failure` — both carry diagnostics.
- `kotlin.script.experimental.api.ScriptDiagnostic` — fields used:
  `severity` (FATAL/ERROR/WARNING/INFO/DEBUG enum), `message`, `sourcePath`,
  `location?.start.line/col`, `code` (factory-id string).
- `JvmScriptCompilationConfigurationBuilder.jvm { dependenciesFromClassContext(KotlinCompileOnly::class, wholeClasspath = true) }`
  — gives the snippet the SAME classpath the executor sees, so IntelliJ
  Platform references resolve identically.
- `toScriptSource(name)` to wrap the string source.

Fallback (degraded): `ScriptEngineManager.getEngineByExtension("kts")` →
cast to `KotlinJsr223ScriptEngineImpl` → `.compile(script)`. Diagnostics
collapse to a single `ScriptException` with `lineNumber()`/`columnNumber()`
— usable but loses warnings. Implement primary; fall back only if
`BasicJvmScriptingHost` isn't reachable on a future IDE build.

Stability: all three are stable since Kotlin 1.3 scripting and already on
the classpath via `kotlin-scripting-jsr223:2.1.20`.

## Threading, EDT, timeout

- Compile is **not** EDT — no Swing, no PSI, no IntelliJ project state.
  Run inside `withContext(Dispatchers.IO) { … }` (or reuse the cached
  `KotlinExecutor.executor` pool). No `onEdtBlocking`, no `ReadAction`.
  No `Project` required — `project: Project?` in the wrapper is a nullable
  formal the compiler doesn't care is unassigned.
- Hard cap **10 000 ms** (CLAUDE.md). Cold start (first compile in this
  JVM) loads kotlin-compiler-embeddable, ~2–3 s; warm ~150–400 ms.
- `withTimeoutOrNull(10_000) { … }` around the suspend `compile(...)`; on
  timeout return `ok=false`, `diagnostics=[]`, `warnings=["Compile timed
  out after 10000 ms"]` — do NOT throw. Caching the compiler (open Qs)
  keeps the budget comfortable on cold calls and trivial on warm ones.

## Edge cases

1. **Empty / whitespace-only `code`** → `ok=true`, `diagnostics=[]`. The
   wrapper still emits a valid `class Plugin { fun run(...) = run { } }`.
2. **Compiler-internal exception** → catch, return single
   `CompileDiagnostic(severity="FATAL", message=<msg>)`, `ok=false`. Never
   propagate.
3. **`kotlin-scripting-jsr223` missing** → unreachable in practice
   (`kotlin-exec.xml` gate); defensively returns a FATAL diagnostic.
4. **Multi-file snippets** (a second top-level declaration the wrapper would
   nest inside a class body, or `wrap=false` input with two `package` lines)
   → ERROR diagnostics, surfaced normally. JSR-223 single-script limitation
   is already called out in `@McpDescription`.
5. **Reference to a class not on the plugin classpath** (e.g. project-only
   library) → `UNRESOLVED_REFERENCE` ERROR with line/column. Normal flow.
6. **Implicit helpers (`read`, `write`, `onEdt`, `project`,
   `pluginDisposable`) with `wrap=true`** → compiles cleanly. Same input
   with `wrap=false` → `UNRESOLVED_REFERENCE` on the helpers — document
   `wrap=true` as the safe default.
7. **Warnings-only outcome** (deprecated API, no errors) → `ok=true`,
   `diagnostics` carries WARNINGs. Callers can apply strict mode themselves.
8. **`return` at top level** (illegal in `kotlin.run { }`) → ERROR with the
   *wrapped* file's line number, not the user's. Offset adjustment is a
   v1.1 item (open Qs).

## Files to create / modify

| Path | Op | What |
|------|----|------|
| `tools/ExecToolset.kt` | Edit | Add `@McpTool("exec.compile_check")` suspend method. Delegates to `KotlinCompileOnly.check(code, wrap)`. NO settings gate, NO AST check, NO confirmation, NO audit log. |
| `exec/KotlinCompileOnly.kt` | Create | Compile-only logic. Owns the cached `BasicJvmScriptingHost` + `JvmScriptCompiler`. Maps `ScriptDiagnostic` → `CompileDiagnostic`. 10 s `withTimeoutOrNull` wrapper. New file (not added to `KotlinExecutor.kt`) so the run-loop stays focused on execution and we don't accidentally couple compile-only to confirmation/AST flow. |
| `model/args/ExecArgs.kt` | Edit | Add `@Serializable CompileCheckArgs`. |
| `model/CompileCheckInfo.kt` | Create | `@Serializable CompileCheckResponse` + `CompileDiagnostic`. |
| `src/testScripting/kotlin/.../KotlinCompileOnlyTest.kt` | Create | See test plan — isolated source set. |

No META-INF changes: tool lives in `ExecToolset`, already registered by
`kotlin-exec.xml`.

## Test plan & gradle implications

`build.gradle.kts` lines 90–97 explicitly **exclude** the scripting stack
from `testRuntimeClasspath` because kotlin-compiler-embeddable bundles older
platform resources (`messages/JavaPsiBundle.properties`, upstream coroutines)
that shadow the IDE's modern copies and crash `BasePlatformTestCase` setUp.
We need compile-only tests without breaking that.

Strategies (recommend **a**):

(a) **Isolated `testScripting` source set** with its own runtime classpath
    that KEEPS the scripting deps but does NOT inherit `BasePlatformTestCase`
    or the IntelliJ test framework. Tests call `KotlinCompileOnly.check(...)`
    directly — pure JVM, no IntelliJ runtime, no resource clash. Wire
    `testScripting` into `check`. ~30 LoC of Gradle plumbing.
(b) Re-include scripting on the existing `test` classpath, filter offending
    resources via jar transforms. Brittle whack-a-mole; reject.
(c) `runIde`-only integration tests; zero Gradle change but no fast feedback.
    Reject for v1.

Test scenarios (under strategy (a)):

1. Empty code → `ok=true`, `diagnostics.isEmpty()`.
2. Trivially valid wrapped snippet (`"42"`, `wrap=true`) → `ok=true`.
3. Syntax error (`"val x ="`) → `ok=false`, at least one ERROR with non-null
   `line`/`column`.
4. Unresolved reference (`"foo.bar()"`, `wrap=true`) → `ok=false`, message
   contains `"Unresolved reference"`, `factoryId` mentions `UNRESOLVED_REFERENCE`.
5. `read { 42 }` with `wrap=true` → `ok=true` (wrapper exposes `read`); same
   input with `wrap=false` → `ok=false`, unresolved on `read`.
6. Constructor-injected 1 ms timeout → `ok=false`, `warnings` mentions
   "timed out", `diagnostics.isEmpty()`.
7. Simulated compiler crash (forced exception via a test-only seam) → caught,
   single FATAL diagnostic, never propagates.

No `BasePlatformTestCase` test — compile-only doesn't need an IntelliJ
project. The `ExecToolset` wrapper is a one-line delegate.

## Estimated effort

~0.5 day (4 h):
- Model + args: 15 min.
- `KotlinCompileOnly` (compiler instance + diagnostic mapping + timeout): 1.5 h.
- `ExecToolset.exec_compile_check` wiring + `@McpDescription`: 30 min.
- Gradle `testScripting` source set + 8 unit tests: 1 h.
- Manual smoke in `runIde`: 30 min.
- Doc regen + polish: 15 min.

## Open questions / risks

- **Support `language: "kotlin" | "java"` for future Java compile-check?**
  Out of scope for v1. Java compile needs `JavaCompiler` and would pull
  this tool into `java-introspect.xml` territory. Defer until concrete demand.
- **Cache the `JvmScriptCompiler` across calls?** Yes — amortises the ~3 s
  cold start to ~200 ms warm. A single static instance held by
  `KotlinCompileOnly`; each call gets a fresh
  `ScriptCompilationConfiguration` so cross-call state leakage is minimal.
  Trade-off: caches a classloader pinning plugin classes, which is the
  same concern `exec.execute_kotlin_in_ide` already has.
- **Should `wrap=true` use the EXACT same `CodeWrapper.wrap` output as the
  executor (Plugin#run wrapper, `read`/`write`/`onEdt` helpers,
  `project`/`pluginDisposable` bindings)?** Yes — this is the whole point.
  Compile passing here MUST imply execute won't reject for unresolved
  references. Reuse `CodeWrapper.wrap` verbatim; do not re-implement.
- **Line/column offset adjustment for `wrap=true`**: wrapper prepends ~20
  lines, so diagnostics report wrapped-script line numbers. v1.1: subtract
  a known `CodeWrapper.userCodeStartLine` for diagnostics in the user-code
  range. Out of scope for v1 — surface raw `line`, document offset in
  `@McpDescription`.
- **Audit log entry?** No, by default — compile is read-only. Optional
  `Logger.getInstance(...).debug(...)` is enough.
- **Should `ok` treat WARNING as failure?** No — `ok=true` iff zero
  ERROR/FATAL. Callers can inspect `diagnostics` themselves for strict mode.

## References

- Sibling tool: `tools/ExecToolset.kt` (`exec_execute_kotlin_in_ide`).
- Wrapper reused verbatim: `exec/CodeWrapper.kt`.
- Engine acquisition pattern to mirror: `exec/KotlinExecutor.obtainEngine`.
- Build-classpath rationale (why scripting is `runtimeOnly` and excluded
  from `testRuntimeClasspath`): `build.gradle.kts` lines 47–97.
- Kotlin scripting:
  `https://github.com/JetBrains/kotlin/tree/master/libraries/scripting/jvm-host`
  for `BasicJvmScriptingHost` / `JvmScriptCompiler`.
- JetBrains MCP equivalent: **none** for snippet-level compile.
  `build_project` exists but rebuilds the whole project — different scope.
