package com.github.xepozz.ide.introspector.exec

import com.github.xepozz.ide.introspector.model.CompileCheckResponse
import com.github.xepozz.ide.introspector.model.CompileDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.JvmScriptCompilationConfigurationBuilder
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

/**
 * Compiles a Kotlin snippet via `kotlin-scripting-jsr223`'s underlying
 * `JvmScriptCompiler` and returns every diagnostic, *without executing the result*.
 *
 * Sibling of [KotlinExecutor]; deliberately not part of it so the run-loop in
 * `exec.execute_kotlin_in_ide` stays focused on execution and we don't accidentally
 * couple compile-only behaviour to the confirmation / AST / audit flow.
 *
 * Hard 10s timeout — the same cap [KotlinExecutor] enforces (see CLAUDE.md "Timeouts").
 * Cold-start latency is ~2–3s (loading `kotlin-compiler-embeddable`), warm ~150–400ms.
 * The compiler instance is cached statically so warm calls amortise the cold cost.
 */
object KotlinCompileOnly {

    private const val TIMEOUT_MS = 10_000L

    /** Cached compiler. First touch loads `kotlin-compiler-embeddable` (~2–3s). */
    @Volatile private var cachedCompiler: JvmScriptCompiler? = null

    private fun compiler(): JvmScriptCompiler =
        cachedCompiler ?: synchronized(this) {
            cachedCompiler ?: JvmScriptCompiler().also { cachedCompiler = it }
        }

    /**
     * Compile-check a Kotlin snippet. Pure-function-like: no project state, no EDT,
     * no IntelliJ services.
     */
    suspend fun check(
        code: String,
        wrap: Boolean,
        timeoutMs: Long = TIMEOUT_MS,
    ): CompileCheckResponse = check(code, wrap, timeoutMs, ::doCompile)

    /**
     * Test seam: lets unit tests inject a fake "compile" lambda to simulate
     * exceptions / timeouts without spinning up the real Kotlin compiler.
     */
    internal suspend fun check(
        code: String,
        wrap: Boolean,
        timeoutMs: Long,
        compileFn: suspend (String) -> ResultWithDiagnostics<*>,
    ): CompileCheckResponse {
        val startNs = System.nanoTime()
        val source = if (wrap) CodeWrapper.wrap(code) else code

        val result: ResultWithDiagnostics<*>? = try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) { compileFn(source) }
            }
        } catch (t: Throwable) {
            return CompileCheckResponse(
                ok = false,
                diagnostics = listOf(
                    CompileDiagnostic(
                        severity = "FATAL",
                        message = "Compiler threw ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}",
                    )
                ),
                warnings = emptyList(),
                durationMs = elapsedMs(startNs),
            )
        }

        if (result == null) {
            return CompileCheckResponse(
                ok = false,
                diagnostics = emptyList(),
                warnings = listOf("Compile timed out after $timeoutMs ms"),
                durationMs = elapsedMs(startNs),
            )
        }

        val diagnostics = result.reports.map { it.toModel() }
        val hasError = diagnostics.any { it.severity == "ERROR" || it.severity == "FATAL" }
        // result.isFailure may flip even with zero ERROR/FATAL — be defensive.
        val ok = !hasError && result is ResultWithDiagnostics.Success<*>
        return CompileCheckResponse(
            ok = ok,
            diagnostics = diagnostics,
            warnings = emptyList(),
            durationMs = elapsedMs(startNs),
        )
    }

    private suspend fun doCompile(source: String): ResultWithDiagnostics<*> {
        val src = StringScriptSource(source, SCRIPT_NAME)
        val config = ScriptCompilationConfiguration { configureJvmDependencies() }
        // JvmScriptCompiler.invoke is `operator suspend (SourceCode, ScriptCompilationConfiguration)`.
        return compiler().invoke(src, config)
    }

    private fun ScriptCompilationConfiguration.Builder.configureJvmDependencies() {
        jvm {
            // Give the snippet the same classpath the executor sees, so IntelliJ
            // Platform references resolve identically to runtime.
            dependenciesFromClassContext(
                KotlinCompileOnly::class,
                wholeClasspath = true,
            )
        }
    }

    private fun ScriptDiagnostic.toModel(): CompileDiagnostic {
        val pos = location?.start
        return CompileDiagnostic(
            severity = severity.name,
            line = pos?.line,
            column = pos?.col,
            file = sourcePath,
            message = message,
            // ScriptDiagnostic.code is an Int; expose it as a stringified factory id.
            factoryId = code.takeIf { it != 0 }?.toString(),
        )
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    private const val SCRIPT_NAME = "exec_compile_check.kts"
}
