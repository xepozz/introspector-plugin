package com.github.xepozz.ide.introspector.exec

import com.github.xepozz.ide.introspector.model.CompileCheckResponse
import com.github.xepozz.ide.introspector.model.CompileDiagnostic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KotlinCompileOnly].
 *
 * Lives in the isolated `testScripting` source set (see build.gradle.kts) because
 * exercising the real Kotlin scripting compiler requires `kotlin-scripting-jsr223`
 * + `kotlin-compiler-embeddable` on the runtime classpath — and those are
 * deliberately excluded from the main `test` source set's runtime classpath
 * (they shadow IDE Platform resources and break BasePlatformTestCase setUp).
 *
 * Tests #1–#5 hit the real compiler; tests #6–#7 use the internal test seam
 * so we don't have to wait for a real compiler timeout / arrange a real crash.
 */
class KotlinCompileOnlyTest {

    // ------------------------------------------------------------------
    // Real-compiler scenarios
    // ------------------------------------------------------------------

    @Test
    fun `empty code wrapped is valid`() = runBlocking {
        val r = KotlinCompileOnly.check(code = "", wrap = true)
        assertOk(r)
        assertTrue("expected no diagnostics, got ${r.diagnostics}", r.diagnostics.isEmpty())
    }

    @Test
    fun `trivial wrapped literal compiles`() = runBlocking {
        val r = KotlinCompileOnly.check(code = "42", wrap = true)
        assertOk(r)
    }

    @Test
    fun `syntax error reports a positioned ERROR`() = runBlocking {
        val r = KotlinCompileOnly.check(code = "val x =", wrap = true)
        assertFalse("syntax error must fail: $r", r.ok)
        val errs = r.diagnostics.filter { it.severity == "ERROR" || it.severity == "FATAL" }
        assertTrue("expected at least one ERROR/FATAL: $r", errs.isNotEmpty())
        assertNotNull("expected line on at least one error", errs.first().line)
    }

    @Test
    fun `unresolved reference produces ERROR mentioning unresolved`() = runBlocking {
        val r = KotlinCompileOnly.check(code = "foo.bar()", wrap = true)
        assertFalse("unresolved reference must fail: $r", r.ok)
        val msg = r.diagnostics.joinToString("\n") { it.message }
        assertTrue("expected message to mention 'unresolved' or 'Unresolved': $msg",
            msg.contains("nresolved"))
    }

    @Test
    fun `wrapper exposes read helper`() = runBlocking {
        // With wrap=true, the implicit `read { ... }` helper is in scope; this should compile.
        val wrapped = KotlinCompileOnly.check(code = "read { 42 }", wrap = true)
        assertOk(wrapped)

        // With wrap=false, `read` isn't defined, so it should fail to resolve.
        val raw = KotlinCompileOnly.check(code = "read { 42 }", wrap = false)
        assertFalse("raw 'read { 42 }' should fail: $raw", raw.ok)
        val msg = raw.diagnostics.joinToString("\n") { it.message }
        assertTrue("expected raw mode to mention 'nresolved': $msg", msg.contains("nresolved"))
    }

    // ------------------------------------------------------------------
    // Internal test-seam scenarios — no real compiler needed
    // ------------------------------------------------------------------

    @Test
    fun `timeout produces ok=false with timeout warning and no diagnostics`() = runBlocking {
        val r = KotlinCompileOnly.check(
            code = "irrelevant",
            wrap = false,
            timeoutMs = 1L,
            compileFn = {
                // Block past the 1 ms deadline.
                delay(50)
                // Never reached.
                ResultWithDiagnostics.Success(value = Unit, reports = emptyList())
            },
        )
        assertFalse("timeout must fail: $r", r.ok)
        assertTrue("diagnostics should be empty on timeout: $r", r.diagnostics.isEmpty())
        assertTrue(
            "warnings should mention timeout: ${r.warnings}",
            r.warnings.any { it.contains("timed out", ignoreCase = true) }
        )
    }

    @Test
    fun `compiler-internal exception is caught and surfaced as FATAL`() = runBlocking {
        val r = KotlinCompileOnly.check(
            code = "irrelevant",
            wrap = false,
            timeoutMs = 10_000L,
            compileFn = { throw IllegalStateException("boom") },
        )
        assertFalse("crash must fail: $r", r.ok)
        assertEquals("expected one synthetic diagnostic: $r", 1, r.diagnostics.size)
        val d = r.diagnostics.single()
        assertEquals("FATAL", d.severity)
        assertTrue("expected message to include the cause: ${d.message}",
            d.message.contains("boom"))
    }

    // ------------------------------------------------------------------

    private fun assertOk(r: CompileCheckResponse) {
        val errs = r.diagnostics.filter { it.severity == "ERROR" || it.severity == "FATAL" }
        assertTrue(
            "expected ok=true (no ERROR/FATAL); got ok=${r.ok}, errs=$errs, all=${r.diagnostics}",
            r.ok && errs.isEmpty()
        )
    }

    @Suppress("unused")
    private fun synthesize(severity: ScriptDiagnostic.Severity, msg: String): CompileDiagnostic =
        CompileDiagnostic(severity = severity.name, message = msg)
}
