package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.PsiStructureWalker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level tests for [PsiStructureWalker]. We exercise the walker against a real Java
 * file produced via `myFixture.configureByText("Foo.java", …)` so the FileViewProvider has a
 * single language root and AST traversal reflects real production behaviour.
 *
 * All walk() / textRangeInfoOf() calls happen inside a read action — both helpers require one,
 * matching how [com.github.xepozz.ide.introspector.tools.PsiToolset] invokes them under
 * `readActionBlocking`.
 *
 * The `textRangeInfoOf(null, null)` and synthetic-document branches don't need the IDE fixture
 * but live here as well — keeping all PsiStructureWalker coverage in one file simplifies the
 * mental model.
 */
class PsiStructureWalkerPlatformTest : BasePlatformTestCase() {

    private fun <T> read(block: () -> T): T {
        val ref = arrayOfNulls<Any>(1)
        ApplicationManager.getApplication().runReadAction {
            @Suppress("UNCHECKED_CAST")
            ref[0] = block() as Any?
        }
        @Suppress("UNCHECKED_CAST")
        return ref[0] as T
    }

    private fun configure(source: String) {
        myFixture.configureByText("Foo.java", source)
    }

    private fun walkDefault(
        maxDepth: Int = 10,
        maxNodes: Int = 1_000,
        includeWhitespace: Boolean = false,
        includeText: Boolean = false,
        truncateNodeTextAt: Int = 80,
        includeInjections: Boolean = false,
    ): PsiStructureWalker.Result = read {
        PsiStructureWalker.walk(
            project = myFixture.project,
            rootFile = myFixture.file,
            hostDocument = myFixture.editor.document,
            maxDepth = maxDepth,
            maxNodes = maxNodes,
            includeWhitespace = includeWhitespace,
            includeText = includeText,
            truncateNodeTextAt = truncateNodeTextAt,
            includeInjections = includeInjections,
        )
    }

    // ============================================================================
    // walk() — basic structural assertions
    // ============================================================================

    fun testWalkSimpleClassProducesNonEmptyTree() {
        configure("class Foo { void bar() {} }")
        val result = walkDefault()

        assertFalse("result.files must be non-empty", result.files.isEmpty())
        val nodes = result.files.first().nodes
        assertFalse("nodes must be non-empty", nodes.isEmpty())

        val psiClasses = nodes.map { it.psiClass }
        assertTrue(
            "expected PsiJavaFile-like root node, got $psiClasses",
            psiClasses.any { it.contains("PsiJavaFile") },
        )
        assertTrue(
            "expected at least one PsiClass node, got $psiClasses",
            psiClasses.any { it.contains("PsiClass") },
        )
        assertTrue(
            "expected at least one PsiMethod node, got $psiClasses",
            psiClasses.any { it.contains("PsiMethod") },
        )
    }

    fun testWalkRespectsMaxNodes() {
        configure("class Foo { void a() {} void b() {} void c() {} }")
        val result = walkDefault(maxNodes = 5)

        assertTrue("nodeCount=${result.nodeCount} must be <= maxNodes=5", result.nodeCount <= 5)
        assertTrue("must be marked truncated when budget exhausted", result.truncated)
    }

    fun testWalkRespectsMaxDepth() {
        configure("class Foo { void bar() { int x = 1; } }")
        val deep = walkDefault(maxDepth = 200).files.first().nodes.size
        val shallow = walkDefault(maxDepth = 1).files.first().nodes.size

        assertTrue(
            "shallow walk ($shallow nodes) must be strictly smaller than deep walk ($deep nodes)",
            shallow < deep,
        )
    }

    // ============================================================================
    // Whitespace include / exclude
    // ============================================================================

    fun testWalkExcludesWhitespaceByDefault() {
        configure("class Foo {\n    void bar() {}\n}\n")
        val nodes = walkDefault(includeWhitespace = false).files.first().nodes

        val hasWhitespace = nodes.any { it.elementType == "WHITE_SPACE" }
        assertFalse("default walk must omit WHITE_SPACE nodes; got $nodes", hasWhitespace)
    }

    fun testWalkIncludesWhitespaceWhenRequested() {
        configure("class Foo {\n    void bar() {}\n}\n")
        val nodes = walkDefault(includeWhitespace = true).files.first().nodes

        val hasWhitespace = nodes.any { it.elementType == "WHITE_SPACE" }
        assertTrue(
            "includeWhitespace=true must surface at least one WHITE_SPACE node; got element types " +
                "${nodes.map { it.elementType }.distinct()}",
            hasWhitespace,
        )
    }

    // ============================================================================
    // Text include / exclude / truncate
    // ============================================================================

    fun testWalkExcludesTextByDefault() {
        configure("class Foo { void bar() {} }")
        val nodes = walkDefault(includeText = false).files.first().nodes

        assertTrue(
            "all nodes must have null text when includeText=false; offenders: " +
                nodes.filter { it.text != null }.map { it.psiClass to it.text },
            nodes.all { it.text == null },
        )
    }

    fun testWalkIncludesTextWhenRequested() {
        configure("class Foo { void bar() {} }")
        val root = walkDefault(includeText = true).files.first().nodes.first()

        assertNotNull("root node text must be non-null when includeText=true", root.text)
        val rootText = root.text!!
        // The root is the file — its text starts at the source's leading non-whitespace
        // "class" keyword. Just check the keyword appears somewhere.
        assertTrue(
            "root node text should contain the original source; got '$rootText'",
            rootText.contains("class") || rootText.contains("Foo"),
        )
    }

    fun testWalkTruncatesNodeText() {
        val source = "class Foo { void bar() { String s = \"" + "x".repeat(200) + "\"; } }"
        configure(source)
        val nodes = walkDefault(includeText = true, truncateNodeTextAt = 10).files.first().nodes

        // Find any truncated text — root or any other long node. The file root will always
        // exceed 10 chars in our fixture.
        val truncated = nodes.mapNotNull { it.text }.filter { it.endsWith("…") }
        assertTrue("expected at least one truncated text ending with '…'", truncated.isNotEmpty())
        for (t in truncated) {
            // Truncated text is "first N chars + ellipsis" — exactly N + 1 codepoints, but in
            // UTF-16 the ellipsis is one char. So length must be exactly truncateNodeTextAt + 1.
            assertEquals(
                "truncated text '$t' must be exactly ${10 + 1} chars (10 + ellipsis)",
                11,
                t.length,
            )
        }
    }

    // ============================================================================
    // Stable ids
    // ============================================================================

    fun testWalkProducesStableIds() {
        configure("class Foo { void bar() {} }")
        val first = walkDefault().files.first().nodes
        val second = walkDefault().files.first().nodes

        assertEquals("two walks must produce the same number of nodes", first.size, second.size)
        for (i in first.indices) {
            assertEquals(
                "node[$i] id must match across walks",
                first[i].id,
                second[i].id,
            )
        }
        assertEquals("root node id is '0'", "0", first.first().id)
        assertNull("root node parentId is null", first.first().parentId)
        // At least one child id of form "0.<n>" — the first child of the root.
        val childIds = first.drop(1).map { it.id }
        assertTrue(
            "expected at least one '0.<n>'-style child id; got $childIds",
            childIds.any { it.startsWith("0.") },
        )
    }

    fun testWalkElementIdsMapMatchesNodeIds() {
        configure("class Foo { void bar() {} }")
        val result = walkDefault()
        val firstFileNodes = result.files.first().nodes

        // Every elementIds[element] must appear as a node id in some walked file.
        val allNodeIds = result.files.flatMap { it.nodes.map { n -> n.id } }.toSet()
        for ((element, id) in result.elementIds) {
            assertTrue(
                "elementIds entry $id (for ${element.javaClass.simpleName}) must exist in walked node ids",
                id in allNodeIds,
            )
        }
        // The root file's id is in elementIds keyed by the PsiFile.
        val rootId = read { result.elementIds[myFixture.file] }
        assertEquals(
            "root PsiFile's id in elementIds must equal the root node id '${firstFileNodes.first().id}'",
            firstFileNodes.first().id,
            rootId,
        )
    }

    // ============================================================================
    // Injections
    // ============================================================================

    fun testWalkInjectionsEmptyWhenNoneRegistered() {
        configure("class Foo { void bar() { String s = \"plain\"; } }")
        val result = walkDefault(includeInjections = true)

        // Plain Java + no SQL/regex/lang-injection plugin → no injections.
        assertTrue(
            "expected no injections for plain Java; got ${result.injections.size}",
            result.injections.isEmpty(),
        )
    }

    fun testWalkAcrossViewProviderFiles() {
        // Java is single-language, so this is a smoke check — at least one file.
        configure("class Foo {}")
        val result = walkDefault()
        assertTrue("expected at least one viewProvider file, got 0", result.files.size >= 1)
    }

    // ============================================================================
    // textRangeInfoOf — pure helper
    // ============================================================================

    fun testTextRangeInfoOfNullRangeReturnsDefaults() {
        val info = PsiStructureWalker.textRangeInfoOf(null, null)
        assertEquals(0, info.startOffset)
        assertEquals(0, info.endOffset)
        assertEquals(1, info.startLine)
        assertEquals(1, info.startColumn)
        assertEquals(1, info.endLine)
        assertEquals(1, info.endColumn)
    }

    fun testTextRangeInfoOfPopulatesLineCol() {
        // Synthetic document: "ab\ncde\nfg". Offsets:
        //   line 0: a(0) b(1) \n(2)
        //   line 1: c(3) d(4) e(5) \n(6)
        //   line 2: f(7) g(8)
        val doc = DocumentImpl("ab\ncde\nfg")
        // Range covering "cde" (offsets 3..6)
        val range = TextRange(3, 6)
        val info = PsiStructureWalker.textRangeInfoOf(range, doc)

        assertEquals(3, info.startOffset)
        assertEquals(6, info.endOffset)
        // 1-based: line 2, column 1 for start; line 2, column 4 for end ("cde" + the newline at col 4).
        assertEquals("expected startLine=2, got $info", 2, info.startLine)
        assertEquals("expected startColumn=1, got $info", 1, info.startColumn)
        assertEquals("expected endLine=2, got $info", 2, info.endLine)
        assertEquals("expected endColumn=4, got $info", 4, info.endColumn)
    }

    fun testTextRangeInfoOfNullDocumentDefaultsLineCol() {
        // With a range but no document, line/column collapse to 1/1 but the offsets are honoured.
        val info = PsiStructureWalker.textRangeInfoOf(TextRange(5, 12), null)
        assertEquals(5, info.startOffset)
        assertEquals(12, info.endOffset)
        assertEquals(1, info.startLine)
        assertEquals(1, info.startColumn)
        assertEquals(1, info.endLine)
        assertEquals(1, info.endColumn)
    }

    fun testTextRangeInfoOfClampsOffsetsBeyondDocument() {
        val doc = DocumentImpl("abc")
        // Out-of-bounds end offset must clamp to the document length without throwing.
        val info = PsiStructureWalker.textRangeInfoOf(TextRange(0, 99), doc)
        assertEquals(0, info.startOffset)
        assertEquals(99, info.endOffset)
        // Clamped offset 99 -> length 3 -> line 0 (1-based: 1), col 4 (offset 3 within line 0).
        assertEquals(1, info.endLine)
        assertEquals(4, info.endColumn)
    }
}
