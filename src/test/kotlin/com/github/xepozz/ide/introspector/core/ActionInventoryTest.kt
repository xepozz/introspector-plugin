package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ActionInventory] helpers that don't need a running IDE.
 *
 * The non-IDE-dependent surface is intentionally small:
 *
 *   - [ActionInventory.isPrefixQuery] — gatekeeper for the registry-level fast-path
 *     (`ActionManagerEx.getActionIdList(prefix)`). Wrong here means we either miss the
 *     fast-path on a query that would benefit, or we feed the registry a regex/wildcard
 *     it can't parse and stall the walk.
 *   - [ActionInventory.CacheKey] — the data class that keys [com.github.xepozz.ide.introspector.core.internal.TtlCache]
 *     entries. Equality semantics must match the plan: two calls with identical args
 *     share a cache entry; differing args do not.
 *
 * The full walk (registry lookup, plugin reverse map, shortcut formatting, timeout
 * accounting) is exercised in `ActionInventoryPlatformTest` because it needs a live
 * `ActionManager`/`KeymapManager`.
 */
class ActionInventoryTest {

    // ====================================================================================
    // SECTION 1. isPrefixQuery — what counts as "alphanumeric prefix"
    // ====================================================================================

    @Test
    fun `isPrefixQuery accepts a simple alphanumeric id`() {
        assertTrue("EditorCopy is a clean alpha id", ActionInventory.isPrefixQuery("EditorCopy"))
        assertTrue("123 is valid", ActionInventory.isPrefixQuery("123"))
        assertTrue("Run1 is valid alphanumeric", ActionInventory.isPrefixQuery("Run1"))
    }

    @Test
    fun `isPrefixQuery accepts dots and underscores`() {
        // The platform uses dotted action ids ("Refactor.Rename", "Main_Toolbar").
        assertTrue(ActionInventory.isPrefixQuery("Refactor.Rename"))
        assertTrue(ActionInventory.isPrefixQuery("Main_Toolbar"))
        assertTrue(ActionInventory.isPrefixQuery("com.intellij.action"))
    }

    @Test
    fun `isPrefixQuery rejects whitespace`() {
        // Whitespace means "free-form text search" — must go through the substring path.
        assertFalse(ActionInventory.isPrefixQuery("a b"))
        assertFalse(ActionInventory.isPrefixQuery(" leading"))
        assertFalse(ActionInventory.isPrefixQuery("trailing "))
    }

    @Test
    fun `isPrefixQuery rejects wildcards and regex metacharacters`() {
        // `getActionIdList` interprets its arg literally; a star / question would silently
        // never match. Force such queries through the substring walker instead.
        assertFalse(ActionInventory.isPrefixQuery("Editor*"))
        assertFalse(ActionInventory.isPrefixQuery("Refactor?"))
        assertFalse(ActionInventory.isPrefixQuery("Run+"))
        assertFalse(ActionInventory.isPrefixQuery("a[b"))
    }

    @Test
    fun `isPrefixQuery rejects null and blank`() {
        assertFalse(ActionInventory.isPrefixQuery(null))
        assertFalse(ActionInventory.isPrefixQuery(""))
        assertFalse(ActionInventory.isPrefixQuery("   "))
    }

    // ====================================================================================
    // SECTION 2. CacheKey — equality / hash semantics
    // ====================================================================================

    @Test
    fun `CacheKey with identical args is equal`() {
        val a = ActionInventory.CacheKey("Editor", null, false, 200)
        val b = ActionInventory.CacheKey("Editor", null, false, 200)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `CacheKey differing only by query is not equal`() {
        val a = ActionInventory.CacheKey("Editor", null, false, 200)
        val b = ActionInventory.CacheKey("Refactor", null, false, 200)
        assertNotEquals(a, b)
    }

    @Test
    fun `CacheKey differing only by providedByPluginId is not equal`() {
        val a = ActionInventory.CacheKey("Editor", "com.intellij", false, 200)
        val b = ActionInventory.CacheKey("Editor", "org.jetbrains.kotlin", false, 200)
        assertNotEquals(a, b)
    }

    @Test
    fun `CacheKey differing only by includeInternal is not equal`() {
        val a = ActionInventory.CacheKey(null, null, false, 200)
        val b = ActionInventory.CacheKey(null, null, true, 200)
        assertNotEquals(a, b)
    }

    @Test
    fun `CacheKey differing only by limit is not equal`() {
        // limit is part of the key because the result list is sliced at `limit` — two callers
        // asking for different page sizes must not share a cached response.
        val a = ActionInventory.CacheKey(null, null, false, 200)
        val b = ActionInventory.CacheKey(null, null, false, 500)
        assertNotEquals(a, b)
    }
}
