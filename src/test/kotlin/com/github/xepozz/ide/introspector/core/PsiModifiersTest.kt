package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PsiModifiers].
 *
 * Two goals:
 *   1. **Spec coverage**: pin the canonical modifier vocabularies and exercise the null
 *      branch of [PsiModifiers.read]. The non-null path requires a live `PsiModifierList`
 *      and is exercised by a separate platform test (`ClassSourceResolver*`).
 *   2. **Documentation by example**: each list is asserted in exactly the source order so
 *      a reordering or accidental rename surfaces here before it reaches a consumer.
 *
 * ## What's covered here
 *
 * - The three modifier vocabularies (METHOD / FIELD / CLASS) — content, ordering, no
 *   duplicates.
 * - The `read(null, …)` branch — the spec promise that an anonymous/synthetic PSI yields
 *   the empty list.
 *
 * The non-null branch of `read` calls `PsiModifierList.hasModifierProperty`, which only
 * works against a real platform-instantiated PsiElement; that lives in the platform
 * test suite.
 */
class PsiModifiersTest {

    // ====================================================================================
    // SECTION 1. METHOD vocabulary
    // ====================================================================================

    @Test
    fun `METHOD list matches the documented JVM method modifiers in source order`() {
        assertEquals(
            listOf(
                "public", "protected", "private", "static", "abstract",
                "final", "synchronized", "native", "default",
            ),
            PsiModifiers.METHOD,
        )
    }

    @Test
    fun `METHOD list has no duplicates`() {
        assertEquals(PsiModifiers.METHOD.size, PsiModifiers.METHOD.toSet().size)
    }

    // ====================================================================================
    // SECTION 2. FIELD vocabulary
    // ====================================================================================

    @Test
    fun `FIELD list matches the documented JVM field modifiers in source order`() {
        assertEquals(
            listOf(
                "public", "protected", "private", "static", "final", "volatile", "transient",
            ),
            PsiModifiers.FIELD,
        )
    }

    @Test
    fun `FIELD list has no duplicates`() {
        assertEquals(PsiModifiers.FIELD.size, PsiModifiers.FIELD.toSet().size)
    }

    @Test
    fun `FIELD list does not contain method-only modifiers`() {
        // Defensive: synchronized/native/abstract aren't legal on fields.
        assertTrue("FIELD must not list 'synchronized'", "synchronized" !in PsiModifiers.FIELD)
        assertTrue("FIELD must not list 'native'", "native" !in PsiModifiers.FIELD)
        assertTrue("FIELD must not list 'abstract'", "abstract" !in PsiModifiers.FIELD)
    }

    // ====================================================================================
    // SECTION 3. CLASS vocabulary
    // ====================================================================================

    @Test
    fun `CLASS list matches the documented JVM class modifiers in source order`() {
        assertEquals(
            listOf(
                "public", "protected", "private", "static", "abstract", "final",
                "sealed", "non-sealed", "default",
            ),
            PsiModifiers.CLASS,
        )
    }

    @Test
    fun `CLASS list has no duplicates`() {
        assertEquals(PsiModifiers.CLASS.size, PsiModifiers.CLASS.toSet().size)
    }

    @Test
    fun `CLASS list includes sealed and non-sealed for modern Java records and sealed types`() {
        assertTrue("CLASS must list 'sealed'", "sealed" in PsiModifiers.CLASS)
        assertTrue("CLASS must list 'non-sealed'", "non-sealed" in PsiModifiers.CLASS)
    }

    // ====================================================================================
    // SECTION 4. read() — null branch
    //
    // The non-null branch requires a real PsiModifierList instance and is exercised by
    // the platform-level resolver tests. Here we only check the documented contract for
    // a null input: yields the empty list, never NPEs, regardless of which vocabulary
    // the caller passed in.
    // ====================================================================================

    @Test
    fun `read returns empty list when modifier list is null - METHOD`() {
        assertEquals(emptyList<String>(), PsiModifiers.read(null, PsiModifiers.METHOD))
    }

    @Test
    fun `read returns empty list when modifier list is null - FIELD`() {
        assertEquals(emptyList<String>(), PsiModifiers.read(null, PsiModifiers.FIELD))
    }

    @Test
    fun `read returns empty list when modifier list is null - CLASS`() {
        assertEquals(emptyList<String>(), PsiModifiers.read(null, PsiModifiers.CLASS))
    }

    @Test
    fun `read returns empty list when modifier list is null and set is empty`() {
        assertEquals(emptyList<String>(), PsiModifiers.read(null, emptyList()))
    }
}
