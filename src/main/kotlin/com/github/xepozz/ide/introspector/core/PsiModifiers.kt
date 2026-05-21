package com.github.xepozz.ide.introspector.core

import com.intellij.psi.PsiModifierList

/**
 * Canonical PSI modifier vocabularies, kept in one place so toolset code and the resolver
 * agree on the per-symbol-kind sets. All entries match constants on [com.intellij.psi.PsiModifier].
 */
object PsiModifiers {
    val METHOD: List<String> = listOf(
        "public", "protected", "private", "static", "abstract",
        "final", "synchronized", "native", "default",
    )
    val FIELD: List<String> = listOf(
        "public", "protected", "private", "static", "final", "volatile", "transient",
    )
    val CLASS: List<String> = listOf(
        "public", "protected", "private", "static", "abstract", "final",
        "sealed", "non-sealed", "default",
    )

    /** Returns the subset of [set] for which [ml] reports `hasModifierProperty == true`.
     *  Null modifier list (anonymous / synthetic PSI) yields the empty list. */
    fun read(ml: PsiModifierList?, set: List<String>): List<String> =
        if (ml == null) emptyList() else set.filter { ml.hasModifierProperty(it) }
}
