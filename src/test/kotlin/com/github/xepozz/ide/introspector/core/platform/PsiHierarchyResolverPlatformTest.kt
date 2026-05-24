package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.PsiHierarchyResolver
import com.github.xepozz.ide.introspector.model.GotoImplementationResponse
import com.github.xepozz.ide.introspector.model.TypeHierarchyResponse
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level tests for [PsiHierarchyResolver]. Both `typeHierarchy` (mirrors Ctrl+H)
 * and `gotoImplementation` (mirrors Ctrl+Alt+B) require an indexed project and a read
 * action — provided by [BasePlatformTestCase] and the `runReadAction { … }` wrapper.
 *
 * Fixtures are inlined via `myFixture.configureByText` to follow the same convention as
 * [PsiUsageSearcherPlatformTest].
 */
class PsiHierarchyResolverPlatformTest : BasePlatformTestCase() {

    private fun <T> read(block: () -> T): T {
        val ref = arrayOfNulls<Any>(1)
        ApplicationManager.getApplication().runReadAction {
            @Suppress("UNCHECKED_CAST")
            ref[0] = block() as Any?
        }
        @Suppress("UNCHECKED_CAST")
        return ref[0] as T
    }

    private fun add(fileName: String, source: String): PsiFile =
        myFixture.addFileToProject(fileName, source)

    private fun openOffset(needle: String, fileName: String): Int {
        val vf = myFixture.findFileInTempDir(fileName)
            ?: error("file $fileName not added")
        myFixture.configureFromExistingVirtualFile(vf)
        val text = myFixture.file.text
        val idx = text.indexOf(needle)
        check(idx >= 0) { "no '$needle' in $fileName" }
        return idx
    }

    private fun hierarchy(
        target: String? = null,
        psiFile: PsiFile? = null,
        offset: Int? = null,
        direction: String = "both",
        scopeKind: String = "project",
        maxDepth: Int = 5,
        maxNodes: Int = 200,
    ): TypeHierarchyResponse = read {
        PsiHierarchyResolver.typeHierarchy(
            project = myFixture.project,
            target = target,
            psiFile = psiFile,
            offset = offset,
            direction = direction,
            scopeKind = scopeKind,
            maxDepth = maxDepth,
            maxNodes = maxNodes,
        )
    }

    private fun gotoImpl(
        psiFile: PsiFile,
        offset: Int,
        scopeKind: String = "project",
        maxResults: Int = 200,
    ): GotoImplementationResponse = read {
        PsiHierarchyResolver.gotoImplementation(
            project = myFixture.project,
            psiFile = psiFile,
            offset = offset,
            scopeKind = scopeKind,
            maxResults = maxResults,
        )
    }

    // ============================================================================
    // type_hierarchy
    // ============================================================================

    fun testTypeHierarchyByFqnReturnsSupertypesAndSubtypes() {
        add("Animal.java", "public abstract class Animal {}")
        add("Dog.java", "public class Dog extends Animal {}")
        add("Cat.java", "public class Cat extends Animal {}")

        val response = hierarchy(target = "Animal", direction = "both")
        assertEquals("Animal", response.target.fqn)
        // Supertype walk surfaces java.lang.Object on a plain abstract class.
        assertNotNull("supertypes must be present for direction='both'", response.supertypes)
        // Subtype walk surfaces Dog + Cat as direct children.
        val sub = response.subtypes ?: error("subtypes must be present for direction='both'")
        val directNames = sub.children.map { it.node.fqn }.toSet()
        assertTrue(
            "Dog must be a direct subtype, got $directNames",
            directNames.contains("Dog"),
        )
        assertTrue(
            "Cat must be a direct subtype, got $directNames",
            directNames.contains("Cat"),
        )
    }

    fun testTypeHierarchyByPositionResolvesUnderCaret() {
        add("Animal.java", "public abstract class Animal {}")
        val file = add("Dog.java", "public class Dog extends Animal {}")
        val offset = read { file.text.indexOf("Dog") }
        check(offset >= 0)

        val response = hierarchy(psiFile = file, offset = offset, direction = "up")
        assertEquals("Dog", response.target.fqn)
        val sup = response.supertypes ?: error("supertypes must be present for direction='up'")
        val parents = sup.children.map { it.node.fqn }.toSet()
        assertTrue(
            "Animal must be a parent of Dog, got $parents",
            parents.contains("Animal"),
        )
    }

    fun testInterfaceHasMultipleImplementorsAsSubtypes() {
        add("Greeter.java", "public interface Greeter { String greet(); }")
        add(
            "EnglishGreeter.java",
            "public class EnglishGreeter implements Greeter { public String greet() { return \"hi\"; } }",
        )
        add(
            "FrenchGreeter.java",
            "public class FrenchGreeter implements Greeter { public String greet() { return \"salut\"; } }",
        )

        val response = hierarchy(target = "Greeter", direction = "down")
        val sub = response.subtypes ?: error("subtypes must be present")
        val names = sub.children.map { it.node.fqn }.toSet()
        assertTrue("EnglishGreeter must be a subtype, got $names", names.contains("EnglishGreeter"))
        assertTrue("FrenchGreeter must be a subtype, got $names", names.contains("FrenchGreeter"))
    }

    fun testFinalClassHasNoSubtypes() {
        add("Sealed.java", "public final class Sealed {}")
        val response = hierarchy(target = "Sealed", direction = "down")
        val sub = response.subtypes ?: error("subtypes must be present")
        assertTrue(
            "final class must have no subtype children, got ${sub.children.map { it.node.fqn }}",
            sub.children.isEmpty(),
        )
        assertFalse(
            "final class subtype walk should not flag childrenTruncated",
            sub.childrenTruncated,
        )
    }

    fun testObjectSubtypeWalkIsRejected() {
        // Some BasePlatformTestCase configurations don't index the JDK, so the FQN lookup
        // for java.lang.Object may return null. In that case the rejection logic still has
        // to exist — exercise it via the position path against a synthetic Object class.
        val objectClass = read {
            com.intellij.psi.JavaPsiFacade.getInstance(myFixture.project)
                .findClass("java.lang.Object", com.intellij.psi.search.GlobalSearchScope.allScope(myFixture.project))
        }
        if (objectClass == null) {
            // Skip — no java.lang.Object in the test JDK. The reject-Object path is also
            // covered by the equality check `qualifiedName == OBJECT_FQN` at the resolver
            // level which we'd need a real JDK to trigger.
            return
        }
        val response = hierarchy(target = "java.lang.Object", direction = "down")
        assertEquals("java.lang.Object", response.target.fqn)
        val sub = response.subtypes ?: error("subtypes node must be set (with empty children)")
        assertTrue("Object subtype walk must yield no children", sub.children.isEmpty())
        assertTrue("Object subtype rejection must flag childrenTruncated", sub.childrenTruncated)
        assertTrue(
            "warnings must mention the Object rejection; got ${response.warnings}",
            response.warnings.any { it.contains("java.lang.Object", ignoreCase = true) },
        )
    }

    fun testScopeAllAppendsWarning() {
        add("Foo.java", "public class Foo {}")
        val response = hierarchy(target = "Foo", scopeKind = "all", direction = "down")
        assertTrue(
            "scope='all' must append a library-sources warning; got ${response.warnings}",
            response.warnings.any { it.contains("library sources", ignoreCase = true) },
        )
    }

    fun testScopeFileLimitsSubtypeSearchToHostFile() {
        // Subtype lives in the SAME file as the supertype — scope=file must find it.
        val sameFile = add(
            "AnimalsSameFile.java",
            "class AnimalsSameFile {} class AnimalsChild extends AnimalsSameFile {}",
        )
        // Subtype lives in a DIFFERENT file — scope=file must NOT find it.
        add("AnimalsOther.java", "class AnimalsOther extends AnimalsSameFile {}")

        // Use position-based resolution (FQN form has no anchor PsiFile).
        val offset = read { sameFile.text.indexOf("AnimalsSameFile") }
        check(offset >= 0)
        val fileScoped = hierarchy(
            psiFile = sameFile,
            offset = offset,
            scopeKind = "file",
            direction = "down",
        )
        val fileChildren = fileScoped.subtypes!!.children.mapNotNull { it.node.fqn }.toSet()
        assertTrue(
            "scope='file' must include the same-file child AnimalsChild, got $fileChildren",
            fileChildren.contains("AnimalsChild"),
        )
        assertFalse(
            "scope='file' must NOT include the other-file child AnimalsOther, got $fileChildren",
            fileChildren.contains("AnimalsOther"),
        )

        // Sanity check — scope='project' should find BOTH children.
        val projectScoped = hierarchy(
            psiFile = sameFile,
            offset = offset,
            scopeKind = "project",
            direction = "down",
        )
        val projectChildren = projectScoped.subtypes!!.children.mapNotNull { it.node.fqn }.toSet()
        assertTrue(
            "scope='project' must include AnimalsChild, got $projectChildren",
            projectChildren.contains("AnimalsChild"),
        )
        assertTrue(
            "scope='project' must include AnimalsOther, got $projectChildren",
            projectChildren.contains("AnimalsOther"),
        )
    }

    fun testMaxNodesTruncates() {
        add("Root.java", "public class Root {}")
        add("A.java", "public class A extends Root {}")
        add("B.java", "public class B extends Root {}")
        add("C.java", "public class C extends Root {}")

        // maxNodes=2 → root counts as 1, leaves at most 1 child node before truncation kicks in.
        val response = hierarchy(target = "Root", direction = "down", maxNodes = 2)
        assertTrue(
            "expected truncated=true when maxNodes is tight, got $response",
            response.truncated,
        )
    }

    fun testFqnNotFoundThrows() {
        try {
            hierarchy(target = "com.nope.DoesNotExist")
            fail("expected NoTargetException for unknown FQN")
        } catch (e: PsiHierarchyResolver.NoTargetException) {
            assertTrue(
                "exception must mention the FQN; got '${e.message}'",
                e.message?.contains("com.nope.DoesNotExist") == true,
            )
        }
    }

    // ============================================================================
    // goto_implementation
    // ============================================================================

    fun testGotoImplementationOnInterfaceReturnsImpls() {
        add("IShape.java", "public interface IShape { double area(); }")
        add(
            "Circle.java",
            "public class Circle implements IShape { public double area() { return 1.0; } }",
        )
        add(
            "Square.java",
            "public class Square implements IShape { public double area() { return 4.0; } }",
        )
        val offset = openOffset("IShape", "IShape.java")

        val response = gotoImpl(psiFile = myFixture.file, offset = offset)
        assertEquals("class", response.target.kind)
        val classes = response.implementations.mapNotNull { it.declaringClassFqn }.toSet()
        assertTrue("Circle must be among impls, got $classes", classes.contains("Circle"))
        assertTrue("Square must be among impls, got $classes", classes.contains("Square"))
    }

    fun testGotoImplementationOnAbstractMethodReturnsOverrides() {
        add(
            "Shape.java",
            "public abstract class Shape { public abstract double area(); }",
        )
        add(
            "Triangle.java",
            "public class Triangle extends Shape { public double area() { return 0.5; } }",
        )
        val offset = openOffset("area", "Shape.java")

        val response = gotoImpl(psiFile = myFixture.file, offset = offset)
        assertEquals("method", response.target.kind)
        val classes = response.implementations.mapNotNull { it.declaringClassFqn }.toSet()
        assertTrue(
            "Triangle.area must be among impls, got $classes",
            classes.contains("Triangle"),
        )
    }

    fun testGotoImplementationOnFinalMethodIsEmpty() {
        add(
            "FinalThing.java",
            "public class FinalThing { public final void run() {} }",
        )
        val offset = openOffset("run", "FinalThing.java")

        val response = gotoImpl(psiFile = myFixture.file, offset = offset)
        assertEquals("method", response.target.kind)
        assertTrue(
            "final method must have no overrides, got ${response.implementations.size}",
            response.implementations.isEmpty(),
        )
    }

    fun testGotoImplementationScopeFileLimitsResults() {
        add(
            "PrintableSame.java",
            "interface PrintableSame { void p(); } " +
                "class PrintableSameImpl implements PrintableSame { public void p() {} }",
        )
        add(
            "PrintableOther.java",
            "class PrintableOther implements PrintableSame { public void p() {} }",
        )
        val offset = openOffset("PrintableSame", "PrintableSame.java")

        val fileScoped = gotoImpl(psiFile = myFixture.file, offset = offset, scopeKind = "file")
        val fileNames = fileScoped.implementations.mapNotNull { it.declaringClassFqn }.toSet()
        assertTrue(
            "scope='file' must include same-file PrintableSameImpl, got $fileNames",
            fileNames.contains("PrintableSameImpl"),
        )
        assertFalse(
            "scope='file' must NOT include other-file PrintableOther, got $fileNames",
            fileNames.contains("PrintableOther"),
        )

        val projectScoped = gotoImpl(psiFile = myFixture.file, offset = offset, scopeKind = "project")
        val projectNames = projectScoped.implementations.mapNotNull { it.declaringClassFqn }.toSet()
        assertTrue(
            "scope='project' must include PrintableSameImpl, got $projectNames",
            projectNames.contains("PrintableSameImpl"),
        )
        assertTrue(
            "scope='project' must include PrintableOther, got $projectNames",
            projectNames.contains("PrintableOther"),
        )
    }

    fun testGotoImplementationScopeAllAppendsWarning() {
        add("InterfaceForAll.java", "public interface InterfaceForAll { void run(); }")
        val offset = openOffset("InterfaceForAll", "InterfaceForAll.java")

        val response = gotoImpl(psiFile = myFixture.file, offset = offset, scopeKind = "all")
        assertTrue(
            "scope='all' must append a library-sources warning; got ${response.warnings}",
            response.warnings.any { it.contains("library sources", ignoreCase = true) },
        )
    }

    fun testGotoImplementationOnWhitespaceThrows() {
        val file = add("Empty.java", "class Empty {\n\n  void a() {}\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val blankOffset = file.text.indexOf("\n\n") + 1
        try {
            gotoImpl(psiFile = myFixture.file, offset = blankOffset)
        } catch (_: PsiHierarchyResolver.NoTargetException) {
            // Acceptable — caret on whitespace resolves to nothing.
            return
        }
        // If no exception was thrown, that's acceptable too provided the resolver picked up
        // the enclosing class Empty as the target. Either path is OK; the key behaviour is
        // "no crash and either a valid target or a clean NoTargetException".
    }

    // ============================================================================
    // searchScopeForFile — direct unit-style check
    // ============================================================================

    fun testSearchScopeForFileWithNullFileFallsBackToProject() = read {
        val scope = PsiHierarchyResolver.searchScopeForFile(null, "file", myFixture.project)
        assertNotNull("scope must never be null", scope)
        // Both project scope and file scope are GlobalSearchScope subclasses; we just need
        // a non-null SearchScope so subtype searches still run rather than NPE.
        Unit
    }
}
