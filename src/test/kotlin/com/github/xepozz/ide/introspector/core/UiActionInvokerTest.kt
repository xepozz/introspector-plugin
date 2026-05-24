package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.exec.UiActionBlocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UiActionInvoker] helpers + [UiActionBlocklist]. None of these tests
 * touch the IntelliJ container — they exercise pure-Kotlin formatters and matchers.
 *
 * The platform integration test ([com.github.xepozz.ide.introspector.core.platform.UiActionInvokerPlatformTest])
 * covers the full `getDataContext + update + perform` round-trip; here we focus on the
 * surfaces that can be tested in isolation: error formatting, presentation-text
 * truncation, and the blocklist matcher's wildcard logic.
 */
class UiActionInvokerTest {

    // ====================================================================================
    // SECTION 1. UiActionBlocklist — exact ids and wildcard patterns
    // ====================================================================================

    @Test
    fun `default blocklist matches well-known dangerous ids exactly`() {
        assertTrue(UiActionBlocklist.matches("Vcs.RefactoringChanges"))
        assertTrue(UiActionBlocklist.matches("Reset_HEAD"))
        assertTrue(UiActionBlocklist.matches("Maven.Reimport"))
    }

    @Test
    fun `default blocklist matches Force pattern case-insensitively`() {
        assertTrue(UiActionBlocklist.matches("Vcs.Git.Push.Force"))
        assertTrue(UiActionBlocklist.matches("ForceDeleteFile"))
        assertTrue(UiActionBlocklist.matches("MyForceUpdater"))
        // Wildcard match is case-insensitive.
        assertTrue(UiActionBlocklist.matches("myforceupdater"))
    }

    @Test
    fun `default blocklist matches Delete and Reset patterns`() {
        assertTrue(UiActionBlocklist.matches("DeleteFile"))
        assertTrue(UiActionBlocklist.matches("Vcs.HardReset"))
        assertTrue(UiActionBlocklist.matches("Reset_HEAD"))
    }

    @Test
    fun `benign action ids do not match the blocklist`() {
        assertFalse(UiActionBlocklist.matches("RunAction"))
        assertFalse(UiActionBlocklist.matches("Build"))
        assertFalse(UiActionBlocklist.matches("QuickJavaDoc"))
        assertFalse(UiActionBlocklist.matches(""))
    }

    @Test
    fun `user-supplied extra ids match by exact comparison`() {
        // Plain id — exact match, case-sensitive.
        assertTrue(UiActionBlocklist.matches("My.Custom.DangerousAction", listOf("My.Custom.DangerousAction")))
        // Wrong case — exact match fails, but if it doesn't also match a default wildcard, no hit.
        assertFalse(UiActionBlocklist.matches("my.custom.dangerousaction", listOf("My.Custom.DangerousAction")))
    }

    @Test
    fun `user-supplied wildcard patterns match like the defaults`() {
        assertTrue(UiActionBlocklist.matches("MyPlugin.NukeAll", listOf("*Nuke*")))
        assertTrue(UiActionBlocklist.matches("MyPlugin.NukeAll", listOf("*nuke*"))) // case-insensitive
        assertFalse(UiActionBlocklist.matches("MyPlugin.Run", listOf("*Nuke*")))
    }

    @Test
    fun `matchesGlob handles only star wildcard and escapes regex specials`() {
        // No '*' → never matches as glob (only the exact-id path can hit).
        assertFalse(UiActionBlocklist.matchesGlob("foo.bar", "foo.bar"))
        // '*' matches anything, including empty.
        assertTrue(UiActionBlocklist.matchesGlob("anything", "*"))
        assertTrue(UiActionBlocklist.matchesGlob("anything", "*anything*"))
        // Regex specials in pattern are escaped — '.' is literal, not "any char".
        assertTrue(UiActionBlocklist.matchesGlob("foo.bar", "foo.*"))
        assertFalse(UiActionBlocklist.matchesGlob("fooXbar", "foo.bar"))
    }

    // ====================================================================================
    // SECTION 2. presentationText truncation
    // ====================================================================================

    @Test
    fun `truncatePresentationText returns null for null input`() {
        assertNull(UiActionInvoker.truncatePresentationText(null))
    }

    @Test
    fun `truncatePresentationText preserves strings at or below the max length`() {
        val exact = "a".repeat(UiActionInvoker.PRESENTATION_TEXT_MAX)
        assertEquals(exact, UiActionInvoker.truncatePresentationText(exact))
        val shorter = "Hello, world"
        assertEquals(shorter, UiActionInvoker.truncatePresentationText(shorter))
    }

    @Test
    fun `truncatePresentationText caps long strings at PRESENTATION_TEXT_MAX including ellipsis`() {
        val tooLong = "a".repeat(UiActionInvoker.PRESENTATION_TEXT_MAX + 50)
        val out = UiActionInvoker.truncatePresentationText(tooLong)
        assertNotNull(out)
        assertEquals(UiActionInvoker.PRESENTATION_TEXT_MAX, out!!.length)
        assertTrue("Expected trailing ellipsis", out.endsWith(UiActionInvoker.TRUNCATION_ELLIPSIS))
    }

    // ====================================================================================
    // SECTION 3. Error-message formatters
    // ====================================================================================

    @Test
    fun `formatActionNotFound renders the action-not-found prefix with the id`() {
        assertEquals("action-not-found:Foo.Bar", UiActionInvoker.formatActionNotFound("Foo.Bar"))
        assertEquals("action-not-found:", UiActionInvoker.formatActionNotFound(""))
    }

    @Test
    fun `formatComponentDetached renders the component-detached prefix with the id`() {
        assertEquals("component-detached:c_a3f2e1b8", UiActionInvoker.formatComponentDetached("c_a3f2e1b8"))
    }
}
