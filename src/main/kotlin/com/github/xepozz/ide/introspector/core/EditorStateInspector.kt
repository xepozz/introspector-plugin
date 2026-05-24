package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.CaretInfo
import com.github.xepozz.ide.introspector.model.EditorState
import com.github.xepozz.ide.introspector.model.FoldInfo
import com.github.xepozz.ide.introspector.model.GutterMarkerInfo
import com.github.xepozz.ide.introspector.model.InlayCountSummary
import com.github.xepozz.ide.introspector.model.LineRange
import com.github.xepozz.ide.introspector.model.SelectionInfo
import com.github.xepozz.ide.introspector.model.SetCaretResponse
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Headless logic for the `editor.*` tool group. Splits out of [EditorToolset] so the caret
 * math + folding/inlay/daemon collection can be exercised against a real `Editor` in
 * platform tests without going through the MCP reflection bridge.
 *
 * Threading: every public entry point assumes the caller is already on the EDT (caret /
 * folding / scrolling APIs are EDT-only). Daemon and document reads further require a read
 * action — we wrap those internally via [ApplicationManager.runReadAction]. The toolset
 * wrapper does the EDT bounce via `onEdtBlocking { … }`.
 */
object EditorStateInspector {

    /** Sentinel "the file exists in the project but no editor is open for it" — caller maps to McpExpectedError. */
    class FileNotOpenException(message: String) : RuntimeException(message)

    /** Sentinel "the file is open but not in a text editor" (binary, hex, image). */
    class NotATextEditorException(message: String) : RuntimeException(message)

    /** Sentinel "no fileUrl was passed and no editor tab is currently focused". */
    class NoActiveEditorException(message: String) : RuntimeException(message)

    /** Sentinel "fileUrl referred to a path that doesn't exist in the VFS". */
    class FileNotFoundException(message: String) : RuntimeException(message)

    /**
     * Resolve a fileUrl (or null → active tab) to its currently-open text Editor. Throws one of
     * the sentinel exceptions above so the toolset wrapper can translate to `McpExpectedError`
     * with consistent messages.
     */
    fun resolveEditor(project: Project, fileUrl: String?): Editor {
        val fem = FileEditorManager.getInstance(project)
        val vf: VirtualFile = if (fileUrl != null) {
            VirtualFileManager.getInstance().findFileByUrl(fileUrl)
                ?: throw FileNotFoundException("No file at url: $fileUrl")
        } else {
            fem.selectedFiles.firstOrNull()
                ?: throw NoActiveEditorException(
                    "No active editor tab. Open a file first, or pass fileUrl from psi.list_open_files."
                )
        }
        // Is the file open at all?
        if (!fem.isFileOpen(vf)) {
            throw FileNotOpenException("File not open: ${vf.url}. Call open_file_in_editor first.")
        }
        // Find the text editor for this file. selectedTextEditor returns the focused text editor
        // — when our vf is the focused file, this is the right one (and the most recently focused
        // split if the file is open in multiple panes).
        val selectedTextEditor = fem.selectedTextEditor
        if (selectedTextEditor != null && selectedTextEditor.virtualFile == vf) {
            return selectedTextEditor
        }
        // Otherwise: enumerate text editors for the file (covers "file open but not focused").
        val textEditor = fem.getEditors(vf)
            .filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>()
            .firstOrNull()
        if (textEditor != null) {
            return textEditor.editor
        }
        // File is open, but only with a non-text editor (image viewer, hex, etc.).
        val fileTypeName = vf.fileType.name
        throw NotATextEditorException(
            "File is not a text editor: ${vf.url} (file type=$fileTypeName)"
        )
    }

    /**
     * Move the primary caret to [targetOffset] (clamped to document length), optionally scroll
     * the viewport, and return a full description of the move.
     */
    fun setCaretAtOffset(editor: Editor, targetOffset: Int, scrollToVisible: Boolean): SetCaretResponse {
        val document = editor.document
        val maxOffset = document.textLength
        val clamped = targetOffset < 0 || targetOffset > maxOffset
        val newOffset = targetOffset.coerceIn(0, maxOffset)
        val oldOffset = editor.caretModel.offset
        editor.caretModel.moveToOffset(newOffset)
        if (scrollToVisible) {
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        val pos = editor.caretModel.logicalPosition
        val url = editor.virtualFile?.url ?: ""
        return SetCaretResponse(
            ok = true,
            fileUrl = url,
            oldOffset = oldOffset,
            newOffset = newOffset,
            line = pos.line + 1,
            column = pos.column + 1,
            madeVisible = scrollToVisible,
            clamped = clamped,
        )
    }

    /**
     * Move the primary caret to a 1-based (line, column) pair. Out-of-range positions clamp to
     * the last line / line end and set `clamped=true` in the response.
     */
    fun setCaretAtLineColumn(
        editor: Editor,
        line: Int,
        column: Int,
        scrollToVisible: Boolean,
    ): SetCaretResponse {
        require(line >= 1) { "line must be >= 1, got $line" }
        require(column >= 1) { "column must be >= 1, got $column" }
        val document = editor.document
        val lineCount = document.lineCount.coerceAtLeast(1)
        val targetLineIdx = (line - 1)
        val clampedLine = targetLineIdx < 0 || targetLineIdx >= lineCount
        val effectiveLineIdx = targetLineIdx.coerceIn(0, lineCount - 1)
        val lineStart = if (document.textLength == 0) 0 else document.getLineStartOffset(effectiveLineIdx)
        val lineEnd = if (document.textLength == 0) 0 else document.getLineEndOffset(effectiveLineIdx)
        val targetCol = (column - 1)
        val clampedColumn = lineStart + targetCol > lineEnd
        val effectiveOffset = (lineStart + targetCol).coerceAtMost(lineEnd).coerceAtLeast(0)
        val oldOffset = editor.caretModel.offset
        editor.caretModel.moveToLogicalPosition(LogicalPosition(effectiveLineIdx, targetCol.coerceAtLeast(0)))
        if (scrollToVisible) {
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        val pos = editor.caretModel.logicalPosition
        val url = editor.virtualFile?.url ?: ""
        return SetCaretResponse(
            ok = true,
            fileUrl = url,
            oldOffset = oldOffset,
            newOffset = effectiveOffset,
            line = pos.line + 1,
            column = pos.column + 1,
            madeVisible = scrollToVisible,
            clamped = clampedLine || clampedColumn,
        )
    }

    /**
     * Build a full snapshot of [editor]'s state — caret(s), selection, viewport, optionally
     * folding / inlay counts / daemon gutter markers. Internally read-actions the daemon /
     * inlay queries; caret + folding + viewport reads don't need a read action.
     */
    fun captureState(
        project: Project,
        editor: Editor,
        includeMultipleCarets: Boolean,
        includeFolding: Boolean,
        includeInlays: Boolean,
        gutterMinSeverityName: String,
    ): EditorState {
        val document = editor.document
        val url = editor.virtualFile?.url ?: ""

        // ---- carets ----
        val primary = editor.caretModel.primaryCaret
        val primaryLogical = primary.logicalPosition
        val primaryInfo = CaretInfo(
            offset = primary.offset,
            line = primaryLogical.line + 1,
            column = primaryLogical.column + 1,
            isPrimary = true,
            selectionStart = if (primary.hasSelection()) primary.selectionStart else null,
            selectionEnd = if (primary.hasSelection()) primary.selectionEnd else null,
        )
        val caretsList = if (includeMultipleCarets) {
            val all = editor.caretModel.allCarets
            // Primary first, then others in document order.
            val others = all.filter { it !== primary }.map { caret ->
                val logical = caret.logicalPosition
                CaretInfo(
                    offset = caret.offset,
                    line = logical.line + 1,
                    column = logical.column + 1,
                    isPrimary = false,
                    selectionStart = if (caret.hasSelection()) caret.selectionStart else null,
                    selectionEnd = if (caret.hasSelection()) caret.selectionEnd else null,
                )
            }
            listOf(primaryInfo) + others
        } else {
            listOf(primaryInfo)
        }

        // ---- selection (primary caret only) ----
        val selectionInfo: SelectionInfo? = if (primary.hasSelection()) {
            val start = primary.selectionStart
            val end = primary.selectionEnd
            val startLine = document.getLineNumber(start.coerceIn(0, document.textLength))
            val endLine = document.getLineNumber(end.coerceIn(0, document.textLength))
            SelectionInfo(
                start = start,
                end = end,
                startLine = startLine + 1,
                endLine = endLine + 1,
                length = end - start,
            )
        } else null

        // ---- visible range ----
        val visibleRange: LineRange? = try {
            val visibleArea = editor.scrollingModel.visibleArea
            if (visibleArea == null || visibleArea.width <= 0 || visibleArea.height <= 0) {
                null
            } else {
                val startLogical = editor.xyToLogicalPosition(java.awt.Point(visibleArea.x, visibleArea.y))
                val endLogical = editor.xyToLogicalPosition(
                    java.awt.Point(visibleArea.x, visibleArea.y + visibleArea.height)
                )
                LineRange(
                    startLine = (startLogical.line + 1).coerceAtLeast(1),
                    endLine = (endLogical.line + 1).coerceAtLeast(1),
                )
            }
        } catch (_: Throwable) {
            null
        }

        // ---- folding ----
        val foldedRanges: List<FoldInfo>? = if (includeFolding) {
            editor.foldingModel.allFoldRegions.map { region ->
                val startOff = region.startOffset.coerceIn(0, document.textLength)
                val endOff = region.endOffset.coerceIn(0, document.textLength)
                val startLine = document.getLineNumber(startOff)
                val endLine = document.getLineNumber(endOff)
                FoldInfo(
                    startOffset = startOff,
                    endOffset = endOff,
                    startLine = startLine + 1,
                    endLine = endLine + 1,
                    placeholder = region.placeholderText,
                    expanded = region.isExpanded,
                )
            }
        } else null

        // ---- inlay counts ----
        val inlayCounts: InlayCountSummary? = if (includeInlays) {
            // InlayModel reads aren't strictly read-action-required, but they touch model state;
            // bracket in a read action for safety. EDT is fine — read action obtains immediately.
            runRead {
                val inlayModel = editor.inlayModel
                val length = document.textLength
                val inline = inlayModel.getInlineElementsInRange(0, length).size
                val block = inlayModel.getBlockElementsInRange(0, length).size
                val afterLine = inlayModel.getAfterLineEndElementsInRange(0, length).size
                InlayCountSummary(
                    inline = inline,
                    block = block,
                    afterLineEnd = afterLine,
                    total = inline + block + afterLine,
                )
            }
        } else null

        // ---- gutter markers ----
        val gutterMarkers: List<GutterMarkerInfo>? = collectGutterMarkers(
            project = project,
            editor = editor,
            minSeverity = parseSeverity(gutterMinSeverityName),
        )

        return EditorState(
            fileUrl = url,
            carets = caretsList,
            selection = selectionInfo,
            visibleRange = visibleRange,
            foldedRanges = foldedRanges,
            inlayCounts = inlayCounts,
            gutterMarkers = gutterMarkers,
        )
    }

    /**
     * Translate one of the allowed names — ERROR, WARNING, WEAK_WARNING, INFO, INFORMATION,
     * ALL — into a [HighlightSeverity] floor. "ALL" / "INFO" map to INFORMATION (lowest
     * meaningful daemon floor).
     */
    fun parseSeverity(name: String): HighlightSeverity {
        return when (name.uppercase()) {
            "ERROR" -> HighlightSeverity.ERROR
            "WARNING" -> HighlightSeverity.WARNING
            "WEAK_WARNING" -> HighlightSeverity.WEAK_WARNING
            "INFO", "INFORMATION", "ALL" -> HighlightSeverity.INFORMATION
            else -> throw IllegalArgumentException(
                "gutterMinSeverity must be one of ERROR / WARNING / WEAK_WARNING / INFO / ALL, got '$name'"
            )
        }
    }

    /**
     * Collect daemon-reported highlights ≥ [minSeverity]. Returns an empty list (not null) when
     * the daemon hasn't produced markers yet — callers distinguish "no markers" from "couldn't
     * query" via the list being empty vs. the field being null at a higher level.
     *
     * Best-effort: catches Throwable from the @ApiStatus.Internal DaemonCodeAnalyzerImpl path
     * and falls back to scanning the MarkupModel directly.
     */
    private fun collectGutterMarkers(
        project: Project,
        editor: Editor,
        minSeverity: HighlightSeverity,
    ): List<GutterMarkerInfo> = runRead {
        val document = editor.document
        val daemonHighlights: List<HighlightInfo> = try {
            DaemonCodeAnalyzerImpl.getHighlights(document, minSeverity, project)
        } catch (_: Throwable) {
            // Fallback: scan the markup model for daemon-owned info-bearing highlighters.
            val markup = (editor.markupModel as? MarkupModelEx)
            if (markup == null) emptyList()
            else {
                val list = ArrayList<HighlightInfo>()
                markup.processRangeHighlightersOverlappingWith(0, document.textLength) { rh ->
                    val info = HighlightInfo.fromRangeHighlighter(rh)
                    if (info != null && info.severity.compareTo(minSeverity) >= 0) {
                        list += info
                    }
                    true
                }
                list
            }
        }
        daemonHighlights.map { info ->
            val anchor = info.actualStartOffset.coerceIn(0, document.textLength)
            GutterMarkerInfo(
                line = document.getLineNumber(anchor) + 1,
                severity = info.severity.name,
                description = info.description,
                toolId = info.inspectionToolId,
            )
        }
    }

    private fun <T> runRead(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        val ref = arrayOfNulls<Any?>(1)
        app.runReadAction {
            @Suppress("UNCHECKED_CAST")
            ref[0] = block() as Any?
        }
        @Suppress("UNCHECKED_CAST")
        return ref[0] as T
    }
}
