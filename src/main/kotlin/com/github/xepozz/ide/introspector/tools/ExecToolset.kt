package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.exec.AstSafetyChecker
import com.github.xepozz.ide.introspector.exec.AuditLogger
import com.github.xepozz.ide.introspector.exec.ConfirmationManager
import com.github.xepozz.ide.introspector.exec.ExecSettings
import com.github.xepozz.ide.introspector.exec.KotlinExecutor
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
}
