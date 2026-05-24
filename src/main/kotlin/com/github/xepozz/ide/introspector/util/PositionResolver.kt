package com.github.xepozz.ide.introspector.util

import com.intellij.openapi.editor.Document

/**
 * Shared offset/line/column resolver used by `psi.*` and `arch.*` tools that accept a
 * caret position as `(offset)` OR `(line, column)`.
 *
 * Extracted from `tools.PsiToolset.resolveOffset` so `tools.ArchitectureToolset` (and any
 * future toolset that needs identical position semantics) can reuse it without duplicating
 * the validation rules — see plan `docs/plans/arch-devkit-mirror.md`.
 *
 * Behaviour (unchanged from the original PsiToolset helper):
 *  - If `offset` is supplied, it wins; bounds-checked against the document length.
 *  - Otherwise both `line` (1-based) and `column` (1-based) are required and the document
 *    must be present. The line index is clamped to `lineCount - 1`, and the resulting
 *    offset is clamped to the end of that line so trailing-column values past EOL still
 *    land on a valid offset rather than throwing.
 */
object PositionResolver {

    fun resolveOffset(document: Document?, offset: Int?, line: Int?, column: Int?): Int {
        if (offset != null) {
            require(document == null || offset in 0..document.textLength) {
                "offset $offset out of bounds (0..${document?.textLength})"
            }
            return offset
        }
        require(line != null && column != null) {
            "position required: pass either `offset`, or both `line` and `column`"
        }
        require(document != null) { "File has no document — cannot resolve line+column" }
        require(line in 1..document.lineCount + 1) {
            "line $line out of bounds (1..${document.lineCount + 1})"
        }
        val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(lineIdx)
        val lineEnd = document.getLineEndOffset(lineIdx)
        val col = (column - 1).coerceAtLeast(0)
        return (lineStart + col).coerceAtMost(lineEnd)
    }
}
