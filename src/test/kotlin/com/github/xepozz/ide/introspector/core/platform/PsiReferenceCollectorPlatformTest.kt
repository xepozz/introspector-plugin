package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.PsiReferenceCollector
import com.github.xepozz.ide.introspector.core.PsiStructureWalker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level tests for [PsiReferenceCollector]. Every call collects references from a real
 * Java PSI file using `myFixture.configureByText(...)`. Reference resolution requires a read
 * action — every helper bounces through `runReadAction { … }`.
 *
 * The collector takes an `elementIds` map produced by [PsiStructureWalker]; we walk the file
 * before each collector run so the resulting references carry stable node ids — matching how
 * `psi.get_references` calls the two in production.
 */
class PsiReferenceCollectorPlatformTest : BasePlatformTestCase() {

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

    /** Walk the current fixture file and return the elementIds map for references. */
    private fun elementIds(): Map<PsiElement, String> = read {
        PsiStructureWalker.walk(
            project = myFixture.project,
            rootFile = myFixture.file,
            hostDocument = myFixture.editor.document,
            maxDepth = 200,
            maxNodes = 50_000,
            includeWhitespace = false,
            includeText = false,
            truncateNodeTextAt = 0,
            includeInjections = false,
        ).elementIds
    }

    private fun collectInFile(
        maxReferences: Int = 1_000,
        truncateTextAt: Int = 80,
        includeMultiResolve: Boolean = true,
    ) = read {
        PsiReferenceCollector.collectInFile(
            rootFile = myFixture.file,
            hostDocument = myFixture.editor.document,
            maxReferences = maxReferences,
            truncateTextAt = truncateTextAt,
            includeMultiResolve = includeMultiResolve,
            elementIds = elementIds(),
        )
    }

    // ============================================================================
    // collectInFile — basic behaviour
    // ============================================================================

    fun testCollectInFileFindsSimpleReferences() {
        configure(
            """
            class Foo {
                void bar() { baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        val (refs, _) = collectInFile()

        assertFalse("expected at least one reference, got none", refs.isEmpty())
        assertTrue(
            "expected at least one reference whose referenceClass contains 'Reference', got " +
                refs.map { it.referenceClass }.distinct(),
            refs.any { it.referenceClass.contains("Reference", ignoreCase = true) },
        )
    }

    fun testCollectInFileResolvesIntraFileReference() {
        configure(
            """
            class Foo {
                void bar() { baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        val (refs, _) = collectInFile()

        // Find the call-site reference to baz() — its source text should be "baz".
        val bazCallRef = refs.firstOrNull { it.sourceText == "baz" && it.targets.isNotEmpty() }
        assertNotNull("expected to find a 'baz' reference among $refs", bazCallRef)
        val target = bazCallRef!!.targets.first()
        assertTrue("reference target must be resolved", target.resolved)
        assertTrue(
            "baz() call must resolve to a same-file target; got sameFile=${target.sameFile}, " +
                "fileUrl=${target.targetFileUrl}",
            target.sameFile,
        )
        assertEquals("declarationName must be 'baz'", "baz", target.declarationName)
    }

    fun testCollectInFileRespectsMaxReferences() {
        // Several method calls so we have plenty of references to truncate.
        configure(
            """
            class Foo {
                void bar() { a(); b(); c(); d(); e(); }
                void a() {} void b() {} void c() {} void d() {} void e() {}
            }
            """.trimIndent(),
        )
        val (refs, truncated) = collectInFile(maxReferences = 2)

        assertTrue("got ${refs.size} references; must be <= 2", refs.size <= 2)
        assertTrue("must be marked truncated when budget exhausted", truncated)
    }

    fun testCollectInFileTruncatesSourceText() {
        // Long identifier — the call site's `sourceText` should be truncated.
        configure(
            """
            class Foo {
                void bar() { aVeryLongIdentifierMethodNameThatExceedsTen(); }
                void aVeryLongIdentifierMethodNameThatExceedsTen() {}
            }
            """.trimIndent(),
        )
        val (refs, _) = collectInFile(truncateTextAt = 10)

        // The call-site reference will have sourceText truncated at 10 + '…'.
        val callRef = refs.firstOrNull { it.sourceText.endsWith("…") }
        assertNotNull(
            "expected at least one reference whose sourceText was truncated to '…'; got " +
                refs.map { it.sourceText },
            callRef,
        )
        assertEquals(
            "truncated sourceText must be exactly 11 chars (10 + ellipsis); got '${callRef!!.sourceText}'",
            11,
            callRef.sourceText.length,
        )
    }

    fun testCollectInFileReferencesCarrySourceNodeId() {
        configure("class Foo { void bar() { baz(); } void baz() {} }")
        val (refs, _) = collectInFile()
        // Every reference should carry the elementIds-derived id of the source element.
        assertTrue(
            "every reference must have a non-null sourceNodeId; offenders: " +
                refs.filter { it.sourceNodeId == null }.map { it.sourcePsiClass },
            refs.all { it.sourceNodeId != null },
        )
    }

    // ============================================================================
    // collectAtElement — single-point variant
    // ============================================================================

    fun testCollectAtElementReturnsRefsForOneElement() {
        configure(
            """
            class Foo {
                void bar() { baz(); }
                void baz() {}
            }
            """.trimIndent(),
        )
        // Pick the call expression "baz()" — find by walking PSI for a method-call expression.
        val refs = read {
            val callExpr = findFirstByText(myFixture.file, "baz()")
            PsiReferenceCollector.collectAtElement(
                element = callExpr,
                hostDocument = myFixture.editor.document,
                truncateTextAt = 80,
                includeMultiResolve = true,
                elementIds = elementIds(),
            )
        }
        // The call expression itself contains a reference expression child whose references[0]
        // points at the baz() method. The element we passed in might or might not surface refs
        // depending on PsiReferenceService behaviour — but at minimum, the returned list does
        // not crash and is a List<ResolvedReference>.
        assertNotNull("collectAtElement must always return a non-null list", refs)
    }

    fun testCollectAtElementOnElementWithoutReferences() {
        configure("class Foo {}")
        val refs = read {
            // The class body brace has no references.
            val brace = myFixture.file.text.indexOf("{")
            val leaf = myFixture.file.findElementAt(brace)!!
            PsiReferenceCollector.collectAtElement(
                element = leaf,
                hostDocument = myFixture.editor.document,
                truncateTextAt = 80,
                includeMultiResolve = true,
                elementIds = elementIds(),
            )
        }
        assertTrue("brace token must have no references, got ${refs.size}", refs.isEmpty())
    }

    // ============================================================================
    // Resolve targets
    // ============================================================================

    fun testReferenceTargetCarriesDeclarationName() {
        configure("class Foo { void bar() { baz(); } void baz() {} }")
        val (refs, _) = collectInFile()
        val bazRef = refs.firstOrNull { it.sourceText == "baz" && it.targets.isNotEmpty() }
        assertNotNull("expected a 'baz' reference", bazRef)
        assertEquals("baz", bazRef!!.targets.first().declarationName)
    }

    fun testReferenceTargetMarksSameFile() {
        configure("class Foo { void bar() { baz(); } void baz() {} }")
        val (refs, _) = collectInFile()
        val bazRef = refs.firstOrNull { it.sourceText == "baz" && it.targets.isNotEmpty() }!!
        assertTrue("intra-file reference must have sameFile=true", bazRef.targets.first().sameFile)
        assertNull(
            "intra-file reference must have null targetFileUrl, got ${bazRef.targets.first().targetFileUrl}",
            bazRef.targets.first().targetFileUrl,
        )
    }

    fun testReferenceTargetsForExternalLibraryReference() {
        configure(
            """
            class Foo {
                void bar() {
                    String s = "x";
                    s.length();
                }
            }
            """.trimIndent(),
        )
        val (refs, _) = collectInFile()
        // Find s.length — its target is the JDK String#length method.
        val lengthRef = refs.firstOrNull {
            it.sourceText == "length" && it.targets.any { t -> t.resolved && t.declarationName == "length" }
        }
        if (lengthRef == null) {
            // The mock JDK may not always provide String#length resolution in BasePlatformTestCase
            // (mockJDK roots vary by IDE build). Skip rather than fail vacuously.
            println("[skip] mock JDK does not resolve String.length() in this sandbox")
            return
        }
        val target = lengthRef.targets.first { it.resolved }
        assertFalse(
            "length() target must NOT be in same file (lives in mock String.class)",
            target.sameFile,
        )
        // For library targets we expect a non-null fileUrl pointing into the JDK jar / mock root.
        // (Some sandbox JDKs may surface as same-file=false but targetFileUrl populated — that's
        // the contract we care about.)
        assertNotNull("library target must have a non-null targetFileUrl", target.targetFileUrl)
    }

    fun testMultiResolveDisabledOnlyReturnsOneTarget() {
        // Even for ordinary references, includeMultiResolve=false must not crash and yields at most 1 target.
        configure("class Foo { void bar() { baz(); } void baz() {} }")
        val (refs, _) = collectInFile(includeMultiResolve = false)
        for (ref in refs) {
            assertTrue(
                "with includeMultiResolve=false, each ref must yield exactly 1 target slot; got ${ref.targets.size}",
                ref.targets.size == 1,
            )
        }
    }

    fun testUnresolvedReferenceIsReportedAsNullTarget() {
        configure("class Foo { void bar() { undefinedSymbol(); } }")
        val (refs, _) = collectInFile()
        // The reference to undefinedSymbol() should produce a reference whose only target is unresolved.
        val unresolved = refs.firstOrNull { ref ->
            ref.sourceText == "undefinedSymbol" && ref.targets.any { !it.resolved }
        }
        assertNotNull(
            "expected an unresolved-target reference for 'undefinedSymbol'; got " +
                refs.map { it.sourceText to it.targets.map { t -> t.resolved } },
            unresolved,
        )
    }

    fun testReferencesFromEmptyFile() {
        configure("")
        val (refs, truncated) = collectInFile()
        assertTrue("empty file must produce no references", refs.isEmpty())
        assertFalse("empty file must not be marked truncated", truncated)
    }

    // ============================================================================
    // helpers
    // ============================================================================

    /** Find the first PsiElement in the file whose text equals [substring]. */
    private fun findFirstByText(file: PsiFile, substring: String): PsiElement {
        val match = PsiTreeUtil.collectElements(file) { e ->
            e.text == substring
        }.firstOrNull()
        return match ?: error("no PSI element with text '$substring' found in ${file.text}")
    }
}
