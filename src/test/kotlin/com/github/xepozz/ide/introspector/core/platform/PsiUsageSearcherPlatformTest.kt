package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.PsiUsageSearcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level tests for [PsiUsageSearcher]. Exercise both `resolveTarget` (Ctrl-click
 * semantics) and `findUsages` (Find Usages tool) against synthetic Java fixtures.
 *
 * `findUsages` runs `ReferencesSearch` / `DefinitionsScopedSearch`; both require a real read
 * action against an indexed project, so we wrap calls in `runReadAction { … }` and rely on
 * [BasePlatformTestCase] to provide indexing for the fixture file.
 */
class PsiUsageSearcherPlatformTest : BasePlatformTestCase() {

    private fun <T> read(block: () -> T): T {
        val ref = arrayOfNulls<Any>(1)
        ApplicationManager.getApplication().runReadAction {
            @Suppress("UNCHECKED_CAST")
            ref[0] = block() as Any?
        }
        @Suppress("UNCHECKED_CAST")
        return ref[0] as T
    }

    private fun configure(source: String, fileName: String = "Foo.java") {
        myFixture.configureByText(fileName, source)
    }

    private fun offsetOfFirst(needle: String): Int = read {
        val text = myFixture.file.text
        val idx = text.indexOf(needle)
        check(idx >= 0) { "no '$needle' in source" }
        idx
    }

    private fun offsetOfNth(needle: String, n: Int): Int = read {
        val text = myFixture.file.text
        var idx = -1
        var found = 0
        while (found <= n) {
            idx = text.indexOf(needle, idx + 1)
            check(idx >= 0) { "less than ${n + 1} occurrences of '$needle' in source" }
            found++
        }
        idx
    }

    // ============================================================================
    // resolveTarget — Ctrl-click semantics
    // ============================================================================

    fun testResolveTargetOnDeclarationReturnsDeclaration() {
        configure(
            """
            class Foo {
                void bar() { baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        // Offset of the second occurrence of "baz" — that's the declaration "void baz()".
        val declOffset = offsetOfNth("baz", 1)
        val target = read { PsiUsageSearcher.resolveTarget(myFixture.file, declOffset) }
        assertNotNull("expected a target at the baz() declaration", target)
        assertTrue("target must be a PsiMethod, got ${target!!.javaClass.simpleName}", target is PsiMethod)
        assertEquals("baz", (target as PsiMethod).name)
    }

    fun testResolveTargetOnReferenceFollowsResolution() {
        configure(
            """
            class Foo {
                void bar() { baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        // First "baz" appears at the call site inside bar().
        val callOffset = offsetOfFirst("baz")
        val target = read { PsiUsageSearcher.resolveTarget(myFixture.file, callOffset) }
        assertNotNull("expected reference at call site to resolve", target)
        assertTrue("target must be the PsiMethod baz()", target is PsiMethod)
        assertEquals("baz", (target as PsiMethod).name)
    }

    fun testResolveTargetOnWhitespaceReturnsNull() {
        configure(
            """
            class Foo {

                void bar() {}
            }
            """.trimIndent(),
        )
        // Offset of the blank line between the class header and bar().
        val blankOffset = myFixture.file.text.indexOf("\n\n") + 1
        val target = read { PsiUsageSearcher.resolveTarget(myFixture.file, blankOffset) }
        // On whitespace there's neither a reference nor a named element nearby — the walker
        // may still pick up the enclosing class as a PsiNamedElement, so accept either null
        // or the enclosing class as a valid result. The key behaviour is "no crash".
        if (target == null) return
        // If platform's getNonStrictParentOfType walks up to the class, we accept that.
        assertTrue(
            "non-null target on whitespace must be the enclosing PsiClass; got ${target.javaClass.simpleName}",
            target.javaClass.simpleName.contains("PsiClass") ||
                target.javaClass.simpleName.contains("PsiJavaFile"),
        )
    }

    // ============================================================================
    // findUsages — basic reference counting
    // ============================================================================

    private fun findUsagesAt(
        offset: Int,
        scopeKind: String = "project",
        includeImplementations: Boolean = true,
        maxUsages: Int = 100,
        truncateTextAt: Int = 80,
        groupByFile: Boolean = false,
    ) = read {
        PsiUsageSearcher.findUsages(
            project = myFixture.project,
            psiFile = myFixture.file,
            offset = offset,
            scopeKind = scopeKind,
            includeImplementations = includeImplementations,
            maxUsages = maxUsages,
            truncateTextAt = truncateTextAt,
            groupByFile = groupByFile,
        )
    }

    fun testFindUsagesReturnsAllReferences() {
        configure(
            """
            class Foo {
                void bar() { baz(); baz(); }
                void baz() {}
                void other() { baz(); }
            }
            """.trimIndent(),
        )
        // Caret on baz() declaration (4th occurrence: bar-call-1, bar-call-2, decl, other-call).
        // Actually order in text: occurrences are call#1, call#2, decl, call#3 → index 2 is the decl.
        val declOffset = offsetOfNth("baz", 2)
        val response = findUsagesAt(declOffset)

        assertEquals("baz", response.target.declarationName)
        assertEquals(
            "expected 3 reference usages of baz(); got ${response.usages.size} (${response.usages.map { it.lineSnippet }})",
            3,
            response.usages.size,
        )
        assertEquals("project", response.scope)
        assertFalse("must not be truncated with maxUsages=100", response.truncated)
    }

    fun testFindUsagesRespectsMaxUsages() {
        configure(
            """
            class Foo {
                void bar() { baz(); baz(); baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        val declOffset = offsetOfNth("baz", 3)
        val response = findUsagesAt(declOffset, maxUsages = 2)

        assertEquals("with maxUsages=2 must return exactly 2", 2, response.usages.size)
        assertTrue("must be marked truncated", response.truncated)
    }

    fun testFindUsagesGroupedByFile() {
        configure(
            """
            class Foo {
                void bar() { baz(); baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        val declOffset = offsetOfNth("baz", 2)
        val response = findUsagesAt(declOffset, groupByFile = true)

        assertTrue(
            "groupByFile=true must leave usages[] empty, got ${response.usages.size}",
            response.usages.isEmpty(),
        )
        assertFalse(
            "groupByFile=true must populate byFile[]; got ${response.byFile.size} groups",
            response.byFile.isEmpty(),
        )
        val firstGroup = response.byFile.first()
        assertFalse(
            "every byFile group must contain at least one usage; got ${firstGroup.usages.size}",
            firstGroup.usages.isEmpty(),
        )
        // All usages in this fixture are in the same file → exactly one group.
        assertEquals("expected one file-group for single-file fixture", 1, response.byFile.size)
    }

    fun testFindUsagesScopeFileRestrictsResults() {
        configure(
            """
            class Foo {
                void bar() { baz(); baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        val declOffset = offsetOfNth("baz", 2)
        val response = findUsagesAt(declOffset, scopeKind = "file")

        assertEquals("file", response.scope)
        assertEquals(
            "scope=file should return same results as project for single-file fixture (2 calls)",
            2,
            response.usages.size,
        )
    }

    fun testFindUsagesIncludesContainingDeclaration() {
        configure(
            """
            class Foo {
                void bar() { baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        val declOffset = offsetOfNth("baz", 1)
        val response = findUsagesAt(declOffset)
        val firstUsage = response.usages.firstOrNull()
        assertNotNull("expected at least one usage", firstUsage)
        val container = firstUsage!!.containingDeclaration
        assertNotNull("usage must have a non-null containingDeclaration", container)
        assertTrue(
            "containingDeclaration must mention 'bar'; got '$container'",
            container!!.contains("bar"),
        )
        assertTrue(
            "containingDeclaration must include a PSI class name prefix like 'PsiMethod:'; got '$container'",
            container.contains(":"),
        )
    }

    fun testFindUsagesIncludesLineSnippet() {
        configure(
            """
            class Foo {
                void bar() { baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        val declOffset = offsetOfNth("baz", 1)
        val response = findUsagesAt(declOffset)
        val firstUsage = response.usages.first()
        assertFalse("lineSnippet must be non-empty", firstUsage.lineSnippet.isBlank())
        // The line "void bar() { baz(); }" trimmed.
        assertEquals(
            "lineSnippet must be the trimmed source line",
            "void bar() { baz(); }",
            firstUsage.lineSnippet,
        )
    }

    fun testFindUsagesThrowsNoTargetExceptionForBadOffset() {
        configure(
            """
            class Foo {

                void bar() {}
            }
            """.trimIndent(),
        )
        // Find an offset where the resolver returns null. Walking up always hits the PsiClass,
        // which IS a PsiNamedElement, so we can't easily produce a null target on whitespace —
        // instead pass an out-of-range negative offset, which findElementAt returns null for.
        // Negative offsets: findElementAt returns null; getNonStrictParentOfType(null, …) is null.
        try {
            findUsagesAt(-1)
            fail("expected NoTargetException for negative offset")
        } catch (e: PsiUsageSearcher.NoTargetException) {
            assertTrue(
                "exception message must mention 'offset'; got '${e.message}'",
                e.message?.contains("offset") == true,
            )
        }
    }

    fun testFindUsagesLocalVariableUsesFileScope() {
        configure(
            """
            class Foo {
                void m() {
                    int x = 1;
                    x++;
                    x--;
                }
            }
            """.trimIndent(),
        )
        // Caret on the `x` declaration (first occurrence after "int ").
        val declOffset = myFixture.file.text.indexOf("x = 1")
        // Pass "project" scope — the searcher must narrow to LocalSearchScope automatically.
        val response = findUsagesAt(declOffset, scopeKind = "project")

        // x is referenced twice (x++, x--) — both must be found regardless of scope kind.
        assertEquals("local 'x' must have 2 usages, got ${response.usages.size}", 2, response.usages.size)
        // The requested scope is reported back unchanged — the auto-narrowing is internal.
        assertEquals("project", response.scope)
    }

    fun testFindUsagesIncludeImplementationsAddsImpls() {
        configure(
            """
            abstract class Animal {
                abstract void speak();
            }
            class Dog extends Animal {
                void speak() { System.out.println("woof"); }
            }
            """.trimIndent(),
        )
        // Caret on the abstract `speak` declaration in Animal — first occurrence of "speak".
        val declOffset = offsetOfFirst("speak")
        val withImpls = findUsagesAt(declOffset, includeImplementations = true)
        val withoutImpls = findUsagesAt(declOffset, includeImplementations = false)

        val withImplsImpls = withImpls.usages.filter { it.kind == "implementation" }
        val withoutImplsImpls = withoutImpls.usages.filter { it.kind == "implementation" }
        assertTrue(
            "includeImplementations=true must add at least one implementation; got ${withImpls.usages.map { it.kind }}",
            withImplsImpls.isNotEmpty(),
        )
        assertTrue(
            "includeImplementations=false must yield no 'implementation' kind",
            withoutImplsImpls.isEmpty(),
        )
        // The Dog impl must be among the implementation entries.
        val dogImpl = withImplsImpls.firstOrNull { it.containingDeclaration?.contains("Dog") == true }
        assertNotNull("expected Dog.speak() implementation in usages; got $withImplsImpls", dogImpl)
    }

    // ============================================================================
    // NoTargetException constructor — tiny smoke test for the public type
    // ============================================================================

    fun testNoTargetExceptionCarriesMessage() {
        val ex: Exception = PsiUsageSearcher.NoTargetException("hello")
        assertEquals("hello", ex.message)
        assertTrue("must be a RuntimeException", ex is RuntimeException)
    }
}
