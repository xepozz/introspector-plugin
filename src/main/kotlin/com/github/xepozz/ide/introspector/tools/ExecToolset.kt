package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.exec.AstSafetyChecker
import com.github.xepozz.ide.introspector.exec.AuditLogger
import com.github.xepozz.ide.introspector.exec.ConfirmationManager
import com.github.xepozz.ide.introspector.exec.ExecSettings
import com.github.xepozz.ide.introspector.exec.KotlinCompileOnly
import com.github.xepozz.ide.introspector.exec.KotlinExecutor
import com.github.xepozz.ide.introspector.model.CompileCheckResponse
import com.github.xepozz.ide.introspector.model.args.ExecuteKotlinArgs
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
data class ExecuteKotlinResponse(
    val ok: Boolean,
    val result: JsonElement = JsonNull,
    val stdout: String? = null,
    val stderr: String? = null,
    val error: String? = null,
    val durationMs: Long,
    val warnings: List<String> = emptyList(),
)

class ExecToolset : McpToolset {

    @McpTool(name = "exec.execute_kotlin_in_ide")
    @McpDescription(
        """
        |Compiles and executes arbitrary Kotlin code inside the running IDE JVM. The escape
        |hatch for whatever the pre-built ui.* / arch.* / screenshot.* tools cannot do.
        |
        |Use this when: no pre-built tool covers your need AND the task can be expressed in
        |a few lines of Kotlin against the IntelliJ Platform API (list breakpoints, toggle
        |a setting, inspect a PSI element, walk virtual files, etc.).
        |
        |Do NOT use this when:
        |  - A pre-built tool can answer (ui.*, arch.*, screenshot.*) — use it; it's 100×
        |    faster than spinning up a Kotlin compiler.
        |  - You want to run a shell command (Runtime.exec/ProcessBuilder are blocked by the
        |    safety checker — use the bundled terminal MCP tool instead).
        |  - The user hasn't enabled this tool: it's off by default and requires per-call
        |    confirmation, so it's a heavyweight option.
        |
        |IMPLICIT BINDINGS in your code (already in scope — no import needed for these names):
        |  - project: Project?           — current focused project, may be null
        |  - pluginDisposable: Disposable — register listeners / subscriptions here; auto-
        |                                   disposed when this call returns
        |  - read { ... }    — wraps ReadAction.compute<T>; use around any PSI / index read
        |  - write { ... }   — wraps WriteCommandAction.runWriteCommandAction; undo-aware
        |  - onEdt { ... }   — wraps ApplicationManager.invokeAndWait; use around Swing access
        |
        |RETURN VALUE: the last expression of your code is serialised to JSON. Primitives,
        |strings, booleans, Maps, and Iterables of these serialise cleanly. IntelliJ API
        |objects (VirtualFile, PsiElement, Project, ...) are not serialisable — they fall
        |back to .toString() with a warning. Prefer returning primitives, Strings, or
        |Map<String, Any?> assembled from the fields you actually need.
        |
        |SAFETY:
        |  - Off by default. Enable in Settings → Tools → IDE Introspector → "Allow Kotlin
        |    code execution".
        |  - Each call shows a modal confirmation dialog by default (user can opt out for
        |    the rest of the session — never across restarts).
        |  - Textual safety blacklist rejects, before compilation: Runtime.getRuntime().exec,
        |    ProcessBuilder, setAccessible(true), System.exit, Class.forName("sun.*").
        |  - Every call recorded to idea.log (category "ide-introspector-audit").
        |  - Hard execution timeout — capped at 10 seconds. Short interactive inspections
        |    only, not long-running operations.
        |
        |Returns: { ok:bool, result:JsonElement, stdout:string?, stderr:string?,
        |error:string?, durationMs:long, warnings:string[] }. On compile/runtime failure
        |ok=false and 'error' carries the formatted stacktrace; the IDE itself stays up.
        |
        |Examples:
        |  // List every tool window id in the current project:
        |  com.intellij.openapi.wm.ToolWindowManager.getInstance(project!!).toolWindowIds.toList()
        |
        |  // Path of the file in the active editor:
        |  read {
        |    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project!!)
        |      .selectedTextEditor?.virtualFile?.path
        |  }
        |
        |  // Number of open editors per file type:
        |  read {
        |    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project!!)
        |      .openFiles.groupBy { it.fileType.name }.mapValues { it.value.size }
        |  }
        """
    )
    suspend fun exec_execute_kotlin_in_ide(
        @McpDescription("Kotlin source. The LAST expression of the wrapped block is the return value (Kotlin 'last expression is the value' semantics).")
        code: String,
        @McpDescription("Hard execution timeout in ms. Hard cap is 10000 (anything larger is clamped). Default 10000.")
        timeoutMs: Long = 10_000,
        @McpDescription("Capture stdout written during execution into the 'stdout' response field. Default true.")
        captureStdout: Boolean = true,
        @McpDescription("Capture stderr written during execution into the 'stderr' response field. Default true.")
        captureStderr: Boolean = true,
        @McpDescription("'edt' (default — wrap on EDT, required for Swing access) or 'background' (don't bounce; for PSI reads etc. wrap with read{} yourself).")
        runOn: String = "edt",
    ): ExecuteKotlinResponse {
        val settings = ExecSettings.getInstance()
        if (!settings.enabled) {
            throw McpExpectedError(
                "exec.execute_kotlin_in_ide is disabled. Enable in Settings → Tools → IDE Introspector → 'Allow Kotlin code execution'.",
                JsonObject(emptyMap()),
            )
        }
        val safety = AstSafetyChecker.check(code)
        if (!safety.ok) {
            AuditLogger.record(code, "blocked-by-safety", 0, safety.reason)
            throw McpExpectedError(safety.reason ?: "Rejected by safety checker", JsonObject(emptyMap()))
        }
        val project = currentCoroutineContext().projectOrNull
            ?: throw McpExpectedError("No focused project available", JsonObject(emptyMap()))
        if (!ConfirmationManager.confirm(project, code)) {
            AuditLogger.record(code, "user-rejected", 0, null)
            throw McpExpectedError("User declined execution", JsonObject(emptyMap()))
        }
        val args = ExecuteKotlinArgs(code, timeoutMs, captureStdout, captureStderr, runOn)
        val r = KotlinExecutor.execute(args, project)
        AuditLogger.record(code,
            outcome = if (r.ok) "ok" else "error",
            durationMs = r.durationMs,
            resultPreview = r.resultPreview)
        return ExecuteKotlinResponse(
            ok = r.ok,
            result = r.result ?: JsonNull,
            stdout = r.stdout,
            stderr = r.stderr,
            error = r.errorMessage,
            durationMs = r.durationMs,
            warnings = r.warnings,
        )
    }

    @McpTool(name = "exec.compile_check")
    @McpDescription(
        """
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
        |Diagnostic line/column with wrap=true refer to the WRAPPED script (the
        |wrapper prepends ~20 lines of imports + helper declarations). Raw line
        |numbers are surfaced as-is in v1; subtract the wrapper offset client-side
        |if you need user-snippet coordinates.
        |
        |Returns: {
        |  ok: Boolean,                          // true iff zero ERROR/FATAL diagnostics
        |  diagnostics: [{
        |    severity: "FATAL" | "ERROR" | "WARNING" | "INFO" | "DEBUG",
        |    line: Int?, column: Int?,           // 1-based; null if no position
        |    file: String?,                      // synthetic name of the wrapped script
        |    message: String,
        |    factoryId: String?                  // diagnostic code; may be null
        |  }],
        |  warnings: [String],                   // tool-side warnings (e.g. "timed out")
        |  durationMs: Long
        |}
        |
        |Examples:
        |  // Verify a generated snippet compiles before running it:
        |  exec.compile_check code="read { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project!!).openFiles.map { it.path } }"
        |
        |  // Lint a self-contained file with its own imports:
        |  exec.compile_check code="import kotlin.math.*; val x: Int = sqrt(4.0).toInt()" wrap=false
        """
    )
    suspend fun exec_compile_check(
        @McpDescription("Kotlin source to compile. Same shape as exec.execute_kotlin_in_ide.code when wrap=true.")
        code: String,
        @McpDescription("If true (default), wrap in the SAME Plugin-class template execute_kotlin_in_ide uses, so 'project', 'pluginDisposable', and read/write/onEdt helpers resolve. If false, compile as a raw top-level .kts script.")
        wrap: Boolean = true,
    ): CompileCheckResponse = KotlinCompileOnly.check(code, wrap)
}
