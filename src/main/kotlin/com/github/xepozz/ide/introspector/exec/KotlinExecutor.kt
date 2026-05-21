package com.github.xepozz.ide.introspector.exec

import com.github.xepozz.ide.introspector.model.args.ExecuteKotlinArgs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.swing.SwingUtilities

/**
 * Top-level entry point for executing user-supplied Kotlin inside the IDE.
 *
 * Architecture:
 *   1. The user's code is wrapped with [CodeWrapper] into a class with a `run(project, disposable)` method.
 *   2. The wrapped script is compiled by the JSR-223 Kotlin ScriptEngine (one fresh engine per call,
 *      so its classloader can be GC'd between executions — fixes the LivePlugin-style retention).
 *   3. The compiled class is instantiated and its `run` method invoked. Result + captured stdout/stderr
 *      are bundled into the response.
 *   4. The bound [Disposable] is disposed at the end so anything the user code registered against it
 *      (listeners, subscriptions) is detached.
 */
object KotlinExecutor {

    private val log = Logger.getInstance(KotlinExecutor::class.java)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ide-introspector-exec").apply { isDaemon = true }
    }

    data class ExecutionResult(
        val ok: Boolean,
        val result: kotlinx.serialization.json.JsonElement?,
        val resultPreview: String?,
        val stdout: String?,
        val stderr: String?,
        val errorMessage: String?,
        val durationMs: Long,
        val warnings: List<String>,
    )

    fun execute(args: ExecuteKotlinArgs, project: Project): ExecutionResult {
        val startNs = System.nanoTime()
        val warnings = mutableListOf<String>()
        val effectiveTimeoutMs = effectiveTimeout(args, warnings)

        val disposable = Disposer.newDisposable("ide-introspector-exec-${System.nanoTime()}")
        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        if (args.captureStdout) System.setOut(PrintStream(TeeOutputStream(stdoutBuf, originalOut), true))
        if (args.captureStderr) System.setErr(PrintStream(TeeOutputStream(stderrBuf, originalErr), true))

        try {
            val script = CodeWrapper.wrap(args.code)
            val engine = obtainEngine()
                ?: return failure(startNs, warnings,
                    "Kotlin ScriptEngine not available — JSR-223 kotlin-scripting-jsr223 must be on the plugin classpath.")

            val future: Future<Any?> = executor.submit(Callable {
                runOn(args.runOn) {
                    // 1) Compile and load the class definition.
                    engine.eval(script)
                    // 2) Instantiate and invoke. We use a tiny second eval to bind locals
                    //    rather than introspect the engine's binding mechanism.
                    val bindings = engine.createBindings()
                    bindings["project"] = project
                    bindings["pluginDisposable"] = disposable
                    engine.eval(
                        """
                        ${CodeWrapper.GENERATED_PACKAGE}.${CodeWrapper.GENERATED_CLASS}()
                            .run(bindings["project"] as com.intellij.openapi.project.Project?,
                                 bindings["pluginDisposable"] as com.intellij.openapi.Disposable)
                        """.trimIndent(),
                        bindings,
                    )
                }
            })

            return try {
                val value: Any? = future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
                val serialised = ResultSerializer.toJson(value)
                ExecutionResult(
                    ok = true,
                    result = serialised.json,
                    resultPreview = value?.toString()?.take(500),
                    stdout = stdoutBuf.toString().ifEmpty { null },
                    stderr = stderrBuf.toString().ifEmpty { null },
                    errorMessage = null,
                    durationMs = elapsedMs(startNs),
                    warnings = warnings + serialised.warnings,
                )
            } catch (t: TimeoutException) {
                future.cancel(true)
                ExecutionResult(false, null, null,
                    stdoutBuf.toString().ifEmpty { null },
                    stderrBuf.toString().ifEmpty { null },
                    "Execution timeout after ${effectiveTimeoutMs} ms",
                    elapsedMs(startNs), warnings)
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                ExecutionResult(false, null, null,
                    stdoutBuf.toString().ifEmpty { null },
                    stderrBuf.toString().ifEmpty { null },
                    "${cause.javaClass.simpleName}: ${cause.message}\n${cause.stackTraceToString().take(2000)}",
                    elapsedMs(startNs), warnings)
            }
        } finally {
            if (args.captureStdout) System.setOut(originalOut)
            if (args.captureStderr) System.setErr(originalErr)
            try { Disposer.dispose(disposable) } catch (_: Throwable) {}
        }
    }

    private fun effectiveTimeout(args: ExecuteKotlinArgs, warnings: MutableList<String>): Long {
        val s = ExecSettings.getInstance()
        val requested = args.timeoutMs.coerceAtLeast(1)
        if (requested > s.maxTimeoutMs) {
            warnings.add("Requested timeoutMs=$requested exceeds maxTimeoutMs=${s.maxTimeoutMs}; clamped.")
            return s.maxTimeoutMs
        }
        return requested
    }

    private fun obtainEngine(): ScriptEngine? = try {
        // Fresh manager + engine per invocation so each call gets its own classloader.
        ScriptEngineManager(KotlinExecutor::class.java.classLoader).getEngineByExtension("kts")
            ?: ScriptEngineManager(KotlinExecutor::class.java.classLoader).getEngineByName("kotlin")
    } catch (t: Throwable) {
        log.warn("Failed to obtain Kotlin ScriptEngine", t)
        null
    }

    private fun <T> runOn(runOn: String, block: () -> T): T = when (runOn) {
        "edt" -> {
            if (SwingUtilities.isEventDispatchThread()) {
                block()
            } else {
                val out = java.util.concurrent.atomic.AtomicReference<Result<T>>()
                ApplicationManager.getApplication().invokeAndWait { out.set(runCatching(block)) }
                requireNotNull(out.get()) { "invokeAndWait returned without populating the result reference" }
                    .getOrThrow()
            }
        }
        else -> block()
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    private fun failure(startNs: Long, warnings: List<String>, message: String): ExecutionResult =
        ExecutionResult(false, null, null, null, null, message, elapsedMs(startNs), warnings)
}

private class TeeOutputStream(
    private val a: java.io.OutputStream,
    private val b: java.io.OutputStream,
) : java.io.OutputStream() {
    override fun write(b1: Int) { a.write(b1); b.write(b1) }
    override fun write(buf: ByteArray, off: Int, len: Int) { a.write(buf, off, len); b.write(buf, off, len) }
    override fun flush() { a.flush(); b.flush() }
}
