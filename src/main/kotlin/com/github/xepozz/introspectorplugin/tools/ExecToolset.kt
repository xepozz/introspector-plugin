package com.github.xepozz.introspectorplugin.tools

import com.github.xepozz.introspectorplugin.exec.AstSafetyChecker
import com.github.xepozz.introspectorplugin.exec.AuditLogger
import com.github.xepozz.introspectorplugin.exec.ConfirmationManager
import com.github.xepozz.introspectorplugin.exec.ExecSettings
import com.github.xepozz.introspectorplugin.exec.KotlinExecutor
import com.github.xepozz.introspectorplugin.model.args.ExecuteKotlinArgs
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
        """Compiles and executes arbitrary Kotlin code inside the running IDE JVM.

IMPLICIT VARIABLES in your code:
  project: Project?           — current open project (or null)
  pluginDisposable: Disposable — auto-disposed when the call returns

IMPLICIT HELPERS:
  read { ... }                — wraps ReadAction.compute
  write { ... }               — wraps WriteCommandAction.runWriteCommandAction
  onEdt { ... }               — runs on EDT and blocks until complete

RETURN VALUE: the last expression of your code is serialised to JSON when possible.
Non-serialisable values (IntelliJ API objects) become .toString() with a warning. Prefer
returning primitives, Strings, or Map<String, Any?>.

SECURITY: opt-in via Settings → Tools → IDE Introspect MCP. Each call shows a confirmation
dialog by default (can be suppressed for the session). A textual blacklist rejects
Runtime.exec, ProcessBuilder, setAccessible(true), System.exit, and sun.* reflection
before compilation.

EXAMPLES:
  com.intellij.openapi.wm.ToolWindowManager.getInstance(project!!).toolWindowIds.toList()
  read { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project!!).selectedTextEditor?.virtualFile?.path }"""
    )
    suspend fun exec_execute_kotlin_in_ide(
        @McpDescription("Kotlin source code; the last expression is the return value") code: String,
        @McpDescription("Hard execution timeout (ms)") timeoutMs: Long = 30_000,
        @McpDescription("Capture stdout into the response") captureStdout: Boolean = true,
        @McpDescription("Capture stderr into the response") captureStderr: Boolean = true,
        @McpDescription("edt | background — where to run") runOn: String = "edt",
    ): ExecuteKotlinResponse {
        val settings = ExecSettings.getInstance()
        if (!settings.enabled) {
            throw McpExpectedError(
                "exec.execute_kotlin_in_ide is disabled. Enable in Settings → Tools → IDE Introspect MCP → 'Allow Kotlin code execution'.",
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
