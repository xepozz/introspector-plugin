package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.EditorStateInspector
import com.github.xepozz.ide.introspector.model.EditorState
import com.github.xepozz.ide.introspector.model.SetCaretResponse
import com.github.xepozz.ide.introspector.util.onEdtBlocking
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject

/**
 * `editor.*` — editor-level inspection and caret mutation. Complements JetBrains' built-in
 * MCP server (which has `open_file_in_editor` / `replace_text_in_file` but no caret control
 * or full editor-state read) and our `psi.*` group (read-only, PSI-tree-centric).
 *
 * Threading: both tools wrap their Swing work in `onEdtBlocking { … }` (10 s cap,
 * ModalityState.any() so the exec-confirmation modal doesn't stall things). Read actions for
 * the daemon / inlay queries are taken inside the inspector itself.
 *
 * Out of scope (JetBrains already handles): opening / closing files, reading file text,
 * replacing text. Per-split caret targeting is also out — `editor.set_caret` targets the
 * most-recently-focused split for the file.
 */
class EditorToolset : McpToolset {

    @McpTool(name = "editor.set_caret")
    @McpDescription(
        """
        |Moves the primary caret to a target position in an open editor and (by default)
        |scrolls it into view. Returns the previous offset so the caller can restore it.
        |
        |Use this when:
        |  - You're about to call psi.get_references / psi.find_usages with scope="at_offset"
        |    and want the IDE's caret to match the position you're querying.
        |  - You need to position the caret before invoking a context-sensitive action
        |    (Goto Declaration, Show Intentions) through ui.invoke_action_on.
        |  - You want to lead the user's eye to a specific line you just analyzed.
        |
        |Do NOT use this when:
        |  - The file isn't open — fails fast with "file not open". Call JetBrains'
        |    `open_file_in_editor` first, then `editor.set_caret`.
        |  - You want to select a range — this only moves the primary caret.
        |  - You only need to read where the caret IS — call `editor.get_state`.
        |
        |Positioning: pass either `offset` (0-based, like psi.*) or `line`+`column`
        |(both 1-based). If both supplied, `offset` wins. Out-of-range positions are
        |clamped to file end and `clamped=true` is set in the response.
        |
        |Split view: targets `FileEditorManager.selectedTextEditor` for that file — the
        |most recently focused split. Per-split targeting is v2.
        |
        |Returns: { ok, fileUrl, oldOffset, newOffset, line, column, madeVisible, clamped }.
        |Save `oldOffset` to undo with a second `editor.set_caret` call.
        |
        |Examples:
        |  fileUrl=null, line=42, column=8        — active tab, row 42 col 8
        |  fileUrl="file:///…/Foo.kt", offset=1024 — explicit file + byte offset
        |  line=42, scrollToVisible=false         — move but don't scroll
        """
    )
    suspend fun editor_set_caret(
        @McpDescription("VFS URL of the file (from psi.list_open_files.url). null → active editor tab.")
        fileUrl: String? = null,
        @McpDescription("0-based document offset. Alternative to line+column. Wins if both supplied.")
        offset: Int? = null,
        @McpDescription("1-based line number. Used when `offset` is null.")
        line: Int? = null,
        @McpDescription("1-based column number. Used with `line`. Default 1.")
        column: Int = 1,
        @McpDescription("Scroll the editor so the caret is visible (ScrollType.MAKE_VISIBLE). Default true.")
        scrollToVisible: Boolean = true,
    ): SetCaretResponse {
        require(offset != null || line != null) {
            "Either `offset` or `line` must be supplied"
        }
        if (line != null) require(line >= 1) { "line must be >= 1, got $line" }
        require(column >= 1) { "column must be >= 1, got $column" }

        val project = requireProject()
        return onEdtBlocking {
            val editor = try {
                EditorStateInspector.resolveEditor(project, fileUrl)
            } catch (e: EditorStateInspector.FileNotFoundException) {
                throw McpExpectedError(e.message ?: "File not found", JsonObject(emptyMap()))
            } catch (e: EditorStateInspector.NoActiveEditorException) {
                throw McpExpectedError(e.message ?: "No active editor", JsonObject(emptyMap()))
            } catch (e: EditorStateInspector.FileNotOpenException) {
                throw McpExpectedError(e.message ?: "File not open", JsonObject(emptyMap()))
            } catch (e: EditorStateInspector.NotATextEditorException) {
                throw McpExpectedError(e.message ?: "Not a text editor", JsonObject(emptyMap()))
            }
            if (offset != null) {
                EditorStateInspector.setCaretAtOffset(editor, offset, scrollToVisible)
            } else {
                EditorStateInspector.setCaretAtLineColumn(editor, line!!, column, scrollToVisible)
            }
        }
    }

    @McpTool(name = "editor.get_state")
    @McpDescription(
        """
        |Returns the full state of an open editor in one call: primary + secondary carets,
        |current selection, visible logical line range, collapsed fold regions, inlay-hint
        |counts by kind, and gutter markers (errors/warnings) the daemon has highlighted.
        |
        |Use this when:
        |  - You need a snapshot of "what the user sees": viewport, caret, selection,
        |    visible errors — without a screenshot.
        |  - You're verifying a UI flow ("did my set_caret take? gutter marker present?")
        |    and want a structured assertion.
        |  - You want a quick count of errors / warnings before deciding whether to call
        |    psi.find_usages or read more code.
        |
        |Do NOT use this when:
        |  - The file isn't open — fails fast like `editor.set_caret`.
        |  - You need PSI structure — call psi.get_structure.
        |  - You need a pixel-accurate snapshot — call screenshot.capture.
        |
        |Tunable (heavy sections are opt-in/opt-out):
        |  - `includeMultipleCarets=true` (default) — enumerates secondary carets.
        |  - `includeFolding=true` (default) — collapsed regions and placeholders.
        |  - `includeInlays=false` (default) — inlay enumeration can saturate the EDT;
        |    only COUNTS by kind are returned even when on.
        |  - `gutterMinSeverity="WARNING"` (default) — one of
        |    ERROR / WARNING / WEAK_WARNING / INFO / ALL.
        |
        |Daemon timing: gutter markers come from `DaemonCodeAnalyzerImpl.getHighlights` and
        |only contain what the daemon has finished computing. On a just-opened file the
        |list may be empty; rerun after a short delay or call psi.* for structural issues.
        |
        |Returns: rich `EditorState` — see plan doc for the data class shape.
        |
        |Examples:
        |  fileUrl=null                                   — full state of the active tab
        |  fileUrl="file:///…/Foo.kt", includeInlays=true — also include inlay counts
        |  gutterMinSeverity="ERROR"                      — only errors in the gutter list
        """
    )
    suspend fun editor_get_state(
        @McpDescription("VFS URL of the file. null → active editor tab.")
        fileUrl: String? = null,
        @McpDescription("Enumerate secondary carets in `carets[]`. Default true.")
        includeMultipleCarets: Boolean = true,
        @McpDescription("Include `foldedRanges[]` (collapsed regions). Default true.")
        includeFolding: Boolean = true,
        @McpDescription("Include `inlayCounts` (count by kind). Default false; can be expensive on large files.")
        includeInlays: Boolean = false,
        @McpDescription("Minimum daemon severity to include in `gutterMarkers[]`. One of ERROR / WARNING / WEAK_WARNING / INFO / ALL. Default WARNING.")
        gutterMinSeverity: String = "WARNING",
    ): EditorState {
        // Validate severity name early so we fail before bouncing to the EDT.
        EditorStateInspector.parseSeverity(gutterMinSeverity)

        val project = requireProject()
        return onEdtBlocking {
            val editor = try {
                EditorStateInspector.resolveEditor(project, fileUrl)
            } catch (e: EditorStateInspector.FileNotFoundException) {
                throw McpExpectedError(e.message ?: "File not found", JsonObject(emptyMap()))
            } catch (e: EditorStateInspector.NoActiveEditorException) {
                throw McpExpectedError(e.message ?: "No active editor", JsonObject(emptyMap()))
            } catch (e: EditorStateInspector.FileNotOpenException) {
                throw McpExpectedError(e.message ?: "File not open", JsonObject(emptyMap()))
            } catch (e: EditorStateInspector.NotATextEditorException) {
                throw McpExpectedError(e.message ?: "Not a text editor", JsonObject(emptyMap()))
            }
            EditorStateInspector.captureState(
                project = project,
                editor = editor,
                includeMultipleCarets = includeMultipleCarets,
                includeFolding = includeFolding,
                includeInlays = includeInlays,
                gutterMinSeverityName = gutterMinSeverity,
            )
        }
    }

    private suspend fun requireProject(): Project = currentCoroutineContext().projectOrNull
        ?: throw McpExpectedError(
            "No focused project. Open a project in this IDE first (editor.* tools operate on an open editor).",
            JsonObject(emptyMap())
        )
}
