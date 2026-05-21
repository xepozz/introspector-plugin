package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.PsiModifiers
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level test for [PsiModifiers.read]. The non-null branch needs a real
 * [com.intellij.psi.PsiModifierList], which we synthesise via
 * [JavaPsiFacade.getElementFactory] — that requires a project, hence [BasePlatformTestCase].
 *
 * Pure-Kotlin unit tests for the static vocabulary lists ([PsiModifiers.METHOD],
 * [PsiModifiers.FIELD], [PsiModifiers.CLASS]) live in
 * `src/test/kotlin/.../core/PsiModifiersTest.kt` so they run without spinning up the IDE.
 */
class PsiModifiersPlatformTest : BasePlatformTestCase() {

    fun testReadOnMethodModifierListReportsActualModifiers() {
        val factory = JavaPsiFacade.getElementFactory(project)
        val method: PsiMethod = factory.createMethodFromText(
            "public static final void foo() {}", null,
        )
        val modifiers = PsiModifiers.read(method.modifierList, PsiModifiers.METHOD)
        assertTrue("expected 'public' in $modifiers", modifiers.contains("public"))
        assertTrue("expected 'static' in $modifiers", modifiers.contains("static"))
        assertTrue("expected 'final' in $modifiers", modifiers.contains("final"))
        assertFalse("did not expect 'abstract' in $modifiers", modifiers.contains("abstract"))
        assertFalse("did not expect 'private' in $modifiers", modifiers.contains("private"))
    }

    fun testReadOnAbstractMethodReportsAbstractAndProtected() {
        val factory = JavaPsiFacade.getElementFactory(project)
        // Abstract methods only exist inside an abstract class; create a containing class so the
        // PSI element is well-formed.
        val cls: PsiClass = factory.createClassFromText(
            "abstract class Holder { protected abstract void bar(); }",
            null,
        ).innerClasses.first()
        val method = cls.methods.first { it.name == "bar" }
        val modifiers = PsiModifiers.read(method.modifierList, PsiModifiers.METHOD)
        assertTrue("expected 'protected' in $modifiers", modifiers.contains("protected"))
        assertTrue("expected 'abstract' in $modifiers", modifiers.contains("abstract"))
        assertFalse("did not expect 'final' in $modifiers", modifiers.contains("final"))
        assertFalse("did not expect 'static' in $modifiers", modifiers.contains("static"))
    }

    fun testReadOnFieldModifierListReportsFieldVocabulary() {
        val factory = JavaPsiFacade.getElementFactory(project)
        val field: PsiField = factory.createFieldFromText(
            "private static final transient int x = 0;", null,
        )
        val modifiers = PsiModifiers.read(field.modifierList, PsiModifiers.FIELD)
        assertTrue("expected 'private' in $modifiers", modifiers.contains("private"))
        assertTrue("expected 'static' in $modifiers", modifiers.contains("static"))
        assertTrue("expected 'final' in $modifiers", modifiers.contains("final"))
        assertTrue("expected 'transient' in $modifiers", modifiers.contains("transient"))
        assertFalse("'volatile' must not be reported for a non-volatile field", modifiers.contains("volatile"))
        // Field vocabulary must NEVER include method-only modifiers even when the field PSI's
        // modifier list might claim them (it never does for plain fields).
        assertFalse("'abstract' is not in the FIELD vocabulary", modifiers.contains("abstract"))
        assertFalse("'synchronized' is not in the FIELD vocabulary", modifiers.contains("synchronized"))
    }

    fun testReadOnClassModifierListReportsClassVocabulary() {
        val factory = JavaPsiFacade.getElementFactory(project)
        val cls: PsiClass = factory.createClassFromText(
            "public final class C { }",
            null,
        )
        // createClassFromText wraps in an outer file; the public final class is the first inner.
        val target = cls.innerClasses.firstOrNull() ?: cls
        val modifiers = PsiModifiers.read(target.modifierList, PsiModifiers.CLASS)
        assertTrue("expected 'public' in $modifiers", modifiers.contains("public"))
        assertTrue("expected 'final' in $modifiers", modifiers.contains("final"))
        assertFalse("did not expect 'abstract' in $modifiers", modifiers.contains("abstract"))
        assertFalse("'transient' is not in the CLASS vocabulary", modifiers.contains("transient"))
        assertFalse("'volatile' is not in the CLASS vocabulary", modifiers.contains("volatile"))
    }

    /**
     * The null branch in [PsiModifiers.read] is already covered by the pure unit test, but
     * exercise it once here too so the platform-level coverage is unambiguous.
     */
    fun testReadOnNullModifierListReturnsEmpty() {
        val result = PsiModifiers.read(null, PsiModifiers.METHOD)
        assertTrue("read(null, …) must return an empty list, got $result", result.isEmpty())
    }
}
