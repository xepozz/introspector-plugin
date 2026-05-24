package com.github.xepozz.ide.introspector.exec

import com.github.xepozz.ide.introspector.model.CompileCheckResponse
import com.github.xepozz.ide.introspector.model.CompileDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.script.Compilable
import javax.script.ScriptEngineManager
import javax.script.ScriptException

/**
 * Compiles a Kotlin snippet via the JSR-223 [javax.script.Compilable] surface of the Kotlin
 * scripting engine (the same one [KotlinExecutor] uses), without executing the result.
 *
 * Sibling of [KotlinExecutor]; deliberately not part of it so the run-loop in
 * `exec.execute_kotlin_in_ide` stays focused on execution and we don't accidentally couple
 * compile-only behaviour to the confirmation / AST / audit flow.
 *
 * Hard 10s timeout — the same cap [KotlinExecutor] enforces (see CLAUDE.md "Timeouts").
 * Cold-start latency is ~2–3s (loading `kotlin-compiler-embeddable`), warm ~150–400ms.
 *
 * NOTE: This implementation lost access to the rich `ResultWithDiagnostics` API after the
 * `JvmScriptCompiler` type was removed in Kotlin 2.x. We collapse all errors into a single
 * "ERROR" diagnostic carrying the [ScriptException] message — sufficient for `ok: false` /
 * `ok: true` semantics and root-cause exposure, but less granular than per-warning entries.
 */
object KotlinCompileOnly {

    private const val TIMEOUT_MS = 10_000L
    private const val SCRIPT_ENGINE_NAME = "kotlin"

    suspend fun check(
        code: String,
        wrap: Boolean,
        timeoutMs: Long = TIMEOUT_MS,
    ): CompileCheckResponse = check(code, wrap, timeoutMs, ::doCompile)

    /**
     * Test seam: lets unit tests inject a fake "compile" lambda. Public (not internal) so
     * [com.github.xepozz.ide.introspector.exec.KotlinCompileOnlyTest] in the `testScripting`
     * source set — which compiles as a separate Kotlin module — can reach it.
     */
    suspend fun check(
        code: String,
        wrap: Boolean,
        timeoutMs: Long,
        compileFn: suspend (String) -> CompileOutcome,
    ): CompileCheckResponse {
        val startNs = System.nanoTime()
        val source = if (wrap) CodeWrapper.wrap(code) else code

        val outcome: CompileOutcome? = try {
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

        if (outcome == null) {
            return CompileCheckResponse(
                ok = false,
                diagnostics = emptyList(),
                warnings = listOf("Compile timed out after $timeoutMs ms"),
                durationMs = elapsedMs(startNs),
            )
        }

        return CompileCheckResponse(
            ok = outcome.ok,
            diagnostics = outcome.diagnostics,
            warnings = emptyList(),
            durationMs = elapsedMs(startNs),
        )
    }

    /**
     * Pair-style return so the test seam can fake a multi-diagnostic outcome without
     * dragging the JSR-223 types into the test signature. Public for the same reason the
     * 4-arg `check(…)` overload is — `testScripting` is a separate Kotlin module.
     */
    data class CompileOutcome(val ok: Boolean, val diagnostics: List<CompileDiagnostic>)

    private fun doCompile(source: String): CompileOutcome {
        val mgr = ScriptEngineManager(KotlinCompileOnly::class.java.classLoader)
        val engine = mgr.getEngineByName(SCRIPT_ENGINE_NAME)
            ?: return CompileOutcome(
                ok = false,
                diagnostics = listOf(
                    CompileDiagnostic(
                        severity = "FATAL",
                        message = "Kotlin ScriptEngine ('$SCRIPT_ENGINE_NAME') unavailable — verify kotlin-scripting-jsr223 is on the runtime classpath.",
                    )
                ),
            )
        val compilable = engine as? Compilable
            ?: return CompileOutcome(
                ok = false,
                diagnostics = listOf(
                    CompileDiagnostic(
                        severity = "FATAL",
                        message = "Kotlin ScriptEngine does not implement javax.script.Compilable — cannot perform a compile-only check.",
                    )
                ),
            )
        return try {
            compilable.compile(source)
            CompileOutcome(ok = true, diagnostics = emptyList())
        } catch (e: ScriptException) {
            CompileOutcome(
                ok = false,
                diagnostics = listOf(
                    CompileDiagnostic(
                        severity = "ERROR",
                        line = e.lineNumber.takeIf { it > 0 },
                        column = e.columnNumber.takeIf { it > 0 },
                        file = e.fileName,
                        message = e.message ?: e.javaClass.simpleName,
                    )
                ),
            )
        } catch (t: Throwable) {
            CompileOutcome(
                ok = false,
                diagnostics = listOf(
                    CompileDiagnostic(
                        severity = "FATAL",
                        message = "Compiler threw ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}",
                    )
                ),
            )
        }
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000
}
