# `exec.compile_check` — Kotlin snippet compile-only validation

## Purpose & motivation

A "cheap lint" sibling of `exec.execute_kotlin_in_ide`: take a snippet of
Kotlin, run it through the same kotlin-scripting-jsr223 compiler we already
host, and return the diagnostics (errors, warnings, info) with line/column
positions — **without executing anything**. Today an agent that wants to
validate generated code before pushing it to the user has to either (a) call
`exec.execute_kotlin_in_ide`, which actually runs the code (with side effects,
confirmation dialog, opt-in gate) or (b) ask the user to apply-then-fix. The
JetBrains MCP server has `build_project` (full Gradle/IntelliJ rebuild) but
NO snippet-level Kotlin compile check — pure gap.

Because nothing runs, this tool is **default-on** and **doesn't need any
confirmation, AST blacklist, audit log, or `ExecSettings.enabled` gate**. The
only precondition is that `kotlin-scripting-jsr223` is on the classpath, which
is already the case in every IDE that loads `META-INF/kotlin-exec.xml`
(i.e. `org.jetbrains.kotlin` plugin present).

**Success criterion:** an agent can call `exec.compile_check { code: "..." }`
in <1 s on a warm compiler and decide whether to surface the snippet to the
user or iterate on it — with the SAME wrapping the executor uses, so a
compile-passing snippet is guaranteed to also compile under
`exec.execute_kotlin_in_ide`.

## Tool specification

### `exec.compile_check`

**Signature:**
```kotlin
@McpTool(name = "exec.compile_check")
@McpDescription(""" |… verbatim block below … """)
suspend fun exec_compile_check(
    @McpDescription("Kotlin source to compile. Same shape as exec.execute_kotlin_in_ide.code when wrap=true.")
    code: String,
    @McpDescription("If true (default), wrap the snippet in the SAME Plugin-class template execute_kotlin_in_ide uses, so 'project', 'pluginDisposable', and the read/write/onEdt helpers resolve. If false, compile the code as a raw top-level .kts script.")
    wrap: Boolean = true,
): CompileCheckResponse
```

**`@McpDescription` draft** (verbatim — trim-margin, kept inside `""" | … """`):

```
|Compiles a Kotlin snippet in-process and returns every compiler diagnostic
|(errors, warnings, info) with line/column positions. NOTHING IS EXECUTED — no
|side effects, no confirmation dialog, no audit log entry.
|
|Use this when:
| - You generated a Kotlin snippet and want to verify it compiles BEFORE
|   asking the user to apply it (or before invoking exec.execute_kotlin_in_ide).
| - You want a fast syntax / type-check on a snippet without spinning up a
|   full Gradle build (use JetBrains' `build_project` for whole-project rebuilds).
| - You want to iterate "fix the next compile error" without running anything.
|
|Do NOT use this when:
| - You actually want the side effects — use exec.execute_kotlin_in_ide.
| - You want to compile a multi-file change against project sources — JSR-223
|   is single-script only; use the IDE's full build via JetBrains' build_project.
| - You want lint/style checks beyond the compiler's own diagnostics — this
|   tool only surfaces what the Kotlin compiler itself reports.
|
|Always-on: this tool is NOT gated by the 'Allow Kotlin code execution'
|setting and does NOT trigger the modal confirmation dialog. Compile is
|read-only. It does require the org.jetbrains.kotlin plugin (same
|prerequisite as exec.execute_kotlin_in_ide); on IDEs without it, the tool
|isn't registered at all.
|
|WRAPPING (wrap=true, default): the snippet is embedded in the same template
|that exec.execute_kotlin_in_ide uses, so the implicit bindings resolve at
|compile time:
| - project: Project?
| - pluginDisposable: Disposable
| - read { } / write { } / onEdt { } helpers
|This guarantees that "compiles here" implies "compiles under exec.execute_*".
|
|wrap=false: the input is compiled as a raw top-level .kts script. Use this
|to lint a self-contained file that already has its own imports / declarations.
|
|Returns: {
|  ok: Boolean,                          // true iff zero ERROR-severity diagnostics
|  diagnostics: [{
|    severity: "FATAL" | "ERROR" | "WARNING" | "INFO" | "DEBUG",
|    line: Int?,                         // 1-based; null if compiler had no position
|    column: Int?,                       // 1-based; null if compiler had no position
|    file: String?,                      // synthetic name of the wrapped script
|    message: String,
|    factoryId: String?                  // e.g. "UNRESOLVED_REFERENCE"; may be null
|  }],
|  warnings: [String],                   // tool-side warnings (e.g. "compiler cold-start")
|  durationMs: Long
|}
|On a snippet with zero errors: ok=true and diagnostics may still be non-empty
|with WARNING/INFO entries.
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
- `code: String` — required, the snippet. No size limit imposed by the tool;
  practical upper bound is whatever the compiler tolerates (~MB range).
- `wrap: Boolean = true` — when `true`, run the input through `CodeWrapper.wrap`
  before compiling, so it lints under the same symbol table executions get.
  When `false`, compile the input as-is.

**Response model** (add to `model/args/ExecArgs.kt` companion or a new
`model/CompileCheckInfo.kt` — see "Files" section):

```kotlin
@Serializable data class CompileCheckResponse(
    val ok: Boolean,
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    val warnings: List<String> = emptyList(),
    val durationMs: Long,
)

@Serializable data class CompileDiagnostic(
    val severity: String,        // "FATAL" | "ERROR" | "WARNING" | "INFO" | "DEBUG"
    val line: Int? = null,       // 1-based
    val column: Int? = null,     // 1-based
    val file: String? = null,    // synthetic file name from compiler
    val message: String,
    val factoryId: String? = null,
)

@Serializable data class CompileCheckArgs(
    val code: String,
    val wrap: Boolean = true,
)
```

## IntelliJ / Kotlin scripting APIs used

Primary path (preferred, rich diagnostics):

- `kotlin.script.experimental.jvmhost.BasicJvmScriptingHost` — the
  scripting-host entry point.
- `kotlin.script.experimental.host.toScriptSource(name)` on the wrapped
  source string.
- `kotlin.script.experimental.jvmhost.JvmScriptCompiler` (`host.compiler`) —
  invoke `compile(source, configuration)` (suspend) and inspect
  `ResultWithDiagnostics<CompiledScript>`.
- `kotlin.script.experimental.api.ScriptDiagnostic` — fields:
  `severity` (FATAL/ERROR/WARNING/INFO/DEBUG), `message`, `sourcePath`,
  `location?.start.line / col`, `code` (the factory-id-like string).
- `kotlin.script.experimental.jvm.JvmScriptCompilationConfigurationBuilder`
  with `jvm { dependenciesFromClassContext(KotlinExecutor::class, wholeClasspath = true) }`
  so the snippet sees the SAME classpath the executor does (so references to
  IntelliJ Platform classes resolve identically).

Fallback path (if BasicJvmScriptingHost isn't reachable in some bundled-IDE
build): use the existing `ScriptEngineManager.getEngineByExtension("kts")`
path, cast to `org.jetbrains.kotlin.script.jsr223.KotlinJsr223ScriptEngineImpl`
(or its interface), and call `.compile(script)`. Diagnostics there come from
`javax.script.ScriptException` with `lineNumber()` / `columnNumber()` — less
rich (single ERROR per call, no warnings) but acceptable as a degraded mode.

Stability:
- `BasicJvmScriptingHost` and `JvmScriptCompiler` are `@KotlinScript`-API
  level — stable enough that the Kotlin team uses them for scratch files in
  IntelliJ itself. Same package set already pulled transitively by
  `kotlin-scripting-jsr223:2.1.20`.
- `ScriptDiagnostic.Severity` is a stable public enum since Kotlin 1.3 scripting.

## Threading & EDT model

- Compile is **not** EDT — no Swing access, no PSI read, no IntelliJ project
  state required (we compile against the plugin classloader's classpath).
- Run the compile call inside `withContext(Dispatchers.IO) { … }` (or the
  same `executor` cached thread pool `KotlinExecutor` already owns), wrapped
  in `withTimeoutOrNull(10_000)` for the 10 s cap.
- No `onEdtBlocking`. No `ReadAction.compute`.
- No project required: `project` is only referenced for typed-binding setup
  *at execute time*; for compile-only we substitute a synthetic `Project?`
  parameter (the wrapper already declares it nullable) and the compiler
  doesn't care it's never assigned.

## Timeout strategy

- Hard cap: **10 000 ms** (`CLAUDE.md` rule).
- First compile in a fresh JVM/classloader pays the kotlin-compiler cold
  start (~2–3 s observed in `exec.execute_kotlin_in_ide` today). Subsequent
  compiles using the SAME `ScriptEngine` / `JvmScriptCompiler` instance run
  in ~150–400 ms.
- `withTimeoutOrNull(10_000) { … }` around the suspend `compile(...)` call;
  on timeout return `ok=false`, `diagnostics=[]`, and a single
  `warnings = ["Compile timed out after 10000 ms"]` entry rather than
  throwing.
- See "Open questions" re: caching the compiler instance across calls to
  amortise the cold start.

## Edge cases

1. **Empty / whitespace-only `code`** → `ok=true`, `diagnostics=[]`,
   `warnings=[]`. The wrapper still produces a valid `class Plugin { fun run(...) = run { } }`
   that compiles cleanly.
2. **Compiler-internal exception** (uncaught crash inside `compile()`) →
   catch, return a single `CompileDiagnostic(severity="FATAL", message=<msg>,
   line=null, column=null)` and `ok=false`. Never propagate.
3. **`kotlin-scripting-jsr223` not on classpath** (e.g. running in an IDE
   without the Kotlin plugin AND someone bypassed the `kotlin-exec.xml`
   condition) → `ok=false`, single FATAL diagnostic
   `"Kotlin scripting host not available — kotlin-scripting-jsr223 missing"`.
   In practice this branch is unreachable because the toolset is only
   registered via `kotlin-exec.xml`.
4. **Multi-file snippets** (input contains a second top-level declaration the
   wrapper would put inside a class body — illegal — OR `wrap=false` input
   uses two `package` lines) → compiler returns ERROR diagnostics, surfaced
   normally. Document in `@McpDescription` that JSR-223 is single-script only.
5. **Snippet references a class not on the plugin classpath** (e.g. some
   library only present in user projects) → ERROR diagnostic
   `UNRESOLVED_REFERENCE` with the right line/column. Normal flow, `ok=false`.
6. **Snippet uses the implicit helpers (`read`, `write`, `onEdt`, `project`,
   `pluginDisposable`)** AND `wrap=true` → compiles cleanly because the
   wrapper declares them in scope.
7. **Same snippet with `wrap=false`** → ERROR `UNRESOLVED_REFERENCE: read`
   (etc.). Document `wrap=true` is the safe default.
8. **Snippet that imports `kotlinx.coroutines`** — the snippet's classpath
   inherits the executor's, so coroutine classes resolve. Note: the plugin
   ships JetBrains-patched coroutines (per build.gradle.kts comments) — same
   for compile as for execute.
9. **Warnings-only outcome** (deprecated API usage but no errors) →
   `ok=true`, `diagnostics` contains the WARNINGs. Caller can choose to
   treat warnings as failures.
10. **Snippet uses `return` at top level** (illegal in `kotlin.run { … }`) →
    ERROR diagnostic with line/column inside the user's portion, but the
    reported line number is the *wrapped* file's line (not the user's). See
    "Open questions" — offset adjustment is a v1.1 improvement.

## Files to create / modify

| Path | Op | What |
|------|----|------|
| `src/main/kotlin/com/github/xepozz/ide/introspector/tools/ExecToolset.kt` | Edit | Add `@McpTool("exec.compile_check")` suspend method. Delegates to `KotlinCompileOnly.check(code, wrap)`. NO `ExecSettings.enabled` check, NO `AstSafetyChecker`, NO `ConfirmationManager`, NO `AuditLogger`. |
| `src/main/kotlin/com/github/xepozz/ide/introspector/exec/KotlinCompileOnly.kt` | Create | Pure compile-only logic. Owns the `BasicJvmScriptingHost` + `JvmScriptCompiler` instances (optionally cached). Maps `ScriptDiagnostic` to `CompileDiagnostic`. Wraps in `withTimeoutOrNull(10_000)`. Recommended new file rather than adding to `KotlinExecutor.kt` so the run-loop stays focused and we don't accidentally couple compile-only to the confirmation/AST flow. |
| `src/main/kotlin/com/github/xepozz/ide/introspector/model/args/ExecArgs.kt` | Edit | Add `@Serializable CompileCheckArgs(code, wrap=true)`. |
| `src/main/kotlin/com/github/xepozz/ide/introspector/model/CompileCheckInfo.kt` | Create | `@Serializable CompileCheckResponse` + `CompileDiagnostic`. Kept separate from `ExecToolset.kt`'s `ExecuteKotlinResponse` to avoid one giant file. |
| `src/test/kotlin/com/github/xepozz/ide/introspector/exec/KotlinCompileOnlyTest.kt` | Create | Unit tests — see test plan. |

No new META-INF wiring: the new tool lives in `ExecToolset`, which is already
registered by `kotlin-exec.xml`. No new `<depends optional="true">` line.

## Test plan & gradle implications

This is the non-trivial part. `build.gradle.kts` (lines 90–97) explicitly
removes `kotlin-scripting-jsr223`, `kotlin-compiler-embeddable`, and friends
from `testRuntimeClasspath` because they bundle older platform resources that
break `BasePlatformTestCase`. We need a way to test compile-only logic
without re-introducing that breakage.

Three viable strategies, from lightest to heaviest — recommend (a) for v1:

(a) **Pure-JVM unit test with an isolated scripting-only test source set**
(`src/testScripting/kotlin/...`). New Gradle source set whose
`testRuntimeClasspath` keeps the scripting deps but does NOT inherit the
`BasePlatformTestCase` framework. Run via a new `testScripting` task wired
into `check`. Tests there call `KotlinCompileOnly.check(...)` directly — no
IntelliJ runtime, so no FileTypeManager preload to break. Pros: isolates the
classpath issue. Cons: ~30 LoC of Gradle plumbing.

(b) **Re-include the scripting deps for the existing `test` source set, but
exclude only the offending resources** (`messages/JavaPsiBundle.properties`
etc.) via a jar-filtering trick. Pros: keeps one test task. Cons: brittle —
the resource list changes between Kotlin versions and we'd be playing
whack-a-mole.

(c) **Integration tests run only in `runIde` / `runPluginVerifier`** — skip
unit testing entirely. Pros: zero gradle changes. Cons: no fast feedback,
diagnostics-shape regressions only caught by manual testing.

Concrete unit-test scenarios (under strategy (a)):

1. **Empty code** → `ok=true`, `diagnostics.isEmpty()`.
2. **Trivially valid wrapped snippet** (`"42"` with `wrap=true`) → `ok=true`,
   any diagnostics are WARNING/INFO only.
3. **Syntax error** (`"val x ="`) → `ok=false`, at least one
   `severity=="ERROR"` diagnostic, `line != null`, `column != null`.
4. **Unresolved reference** (`"foo.bar()"` with `wrap=true`) → `ok=false`,
   diagnostic message contains `"Unresolved reference"` and `factoryId`
   contains `"UNRESOLVED_REFERENCE"`.
5. **Uses implicit helpers (`read { 42 }`) with `wrap=true`** → `ok=true`
   (compiles because the wrapper declares `read`).
6. **Same input with `wrap=false`** → `ok=false`, unresolved reference on
   `read`.
7. **Compiler crash simulation** (inject a malformed source path) → caught,
   returns FATAL diagnostic, never propagates.
8. **Timeout** (parameterised test with a 1 ms hard timeout substituted via
   constructor arg) → `ok=false`, single `warnings` entry mentioning
   "timed out", no diagnostics.

NO platform test (`BasePlatformTestCase`) for this feature — compile-only
logic doesn't need an IntelliJ project. The `KotlinCompileOnly.check`
function takes only `(code: String, wrap: Boolean)` and returns
`CompileCheckResponse`. The `ExecToolset` wrapper is one `delegate` call —
not worth its own platform test.

## Estimated effort

~0.5 day total (4 h):
- `CompileCheckInfo` model + `CompileCheckArgs`: 15 min.
- `KotlinCompileOnly` implementation incl. diagnostic mapping and 10 s
  timeout wrapper: 1.5 h.
- `ExecToolset.exec_compile_check` wiring + `@McpDescription`: 30 min.
- Gradle `testScripting` source set (strategy (a)) + 8 unit tests: 1 h.
- Manual smoke test in `runIde`: 30 min.
- Doc regeneration + plan polish: 15 min.

## Open questions / risks

- **Should we cache the `BasicJvmScriptingHost` / `JvmScriptCompiler` across
  calls?** Likely yes — a single static instance held by `KotlinCompileOnly`
  amortises the ~3 s cold start to ~200 ms warm. Each compile gets a fresh
  `ScriptCompilationConfiguration`, so cross-call state leakage is minimal.
  Risk: the compiler holds a classloader pinning the plugin's classes, which
  could complicate plugin reload during dev. Decision for v1: **cache** — the
  performance win is large and `exec.execute_kotlin_in_ide` already has the
  reload-pinning concern.
- **Should `wrap=true` use the EXACT same `CodeWrapper.wrap` output the
  executor uses (incl. `Plugin#run`, `read`/`write`/`onEdt` helpers,
  `project`/`pluginDisposable` bindings)?** **Yes.** This is the whole point
  of `wrap=true`: compile passing here MUST imply
  `exec.execute_kotlin_in_ide` won't reject for unresolved references. Reuse
  `CodeWrapper.wrap` verbatim, do not re-implement.
- **Should we support `language: "kotlin" | "java"` for future Java
  compile-checking?** **Out of scope for v1.** Java compile would need the
  IDE's `JavaCompiler` and would also require `com.intellij.modules.java`,
  pulling `exec.compile_check` into `java-introspect.xml` territory. Defer
  until concrete demand exists.
- **Line/column offset adjustment for `wrap=true`**: the wrapper prepends
  ~20 lines before the user code. Diagnostics report wrapped-script line
  numbers, which are confusing for callers. v1.1 improvement: subtract a
  known offset (`CodeWrapper.userCodeStartLine`) for diagnostics whose line
  is in the user-code range, leaving wrapper-internal errors alone. Out of
  scope for v1 — surface the raw `line` and document the offset in
  `@McpDescription`.
- **Should an audit log entry be written?** No, by default — compile is
  read-only. If someone really wants observability, a debug-level
  `Logger.getInstance(...).debug(...)` call inside `KotlinCompileOnly` is
  enough.
- **Should `ok` consider WARNING as failure?** No — `ok=true` iff zero ERROR
  or FATAL diagnostics. Callers who want strict mode can inspect
  `diagnostics.any { it.severity in ("ERROR", "FATAL") }` themselves.

## References

- Sibling tool: `tools/ExecToolset.kt` — `exec_execute_kotlin_in_ide`.
- Wrapper reused verbatim: `exec/CodeWrapper.kt`.
- Engine acquisition pattern to mirror: `exec/KotlinExecutor.obtainEngine`.
- Build-classpath rationale (why scripting is `runtimeOnly` and excluded
  from `testRuntimeClasspath`): `build.gradle.kts` lines 47–97.
- Kotlin scripting docs:
  `https://github.com/Kotlin/KEEP/blob/master/proposals/scripting-support.md`
  and `https://github.com/JetBrains/kotlin/tree/master/libraries/scripting/jvm-host`
  for `BasicJvmScriptingHost` / `JvmScriptCompiler`.
- JetBrains MCP equivalent: **none** for snippet-level compile. `build_project`
  exists but rebuilds the whole project — fundamentally different scope.
