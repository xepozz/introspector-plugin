package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Response of `editor.set_caret`. Confirms the move so the caller can:
 *  - verify the new position (`newOffset` / `line` / `column`),
 *  - undo via a follow-up `editor.set_caret(offset = oldOffset)`,
 *  - detect when their target was past EOF and got clamped (`clamped=true`).
 *
 * `line`/`column` are 1-based and reflect the post-clamp position. `madeVisible` is true when
 * the editor's `ScrollingModel.scrollToCaret(MAKE_VISIBLE)` was called (i.e. `scrollToVisible`
 * was true). Note: `madeVisible=true` does NOT guarantee the caret is now on-screen — the
 * scroll request is async and the viewport may still be settling.
 */
@Serializable
data class SetCaretResponse(
    val ok: Boolean,
    val fileUrl: String,
    val oldOffset: Int,
    val newOffset: Int,
    val line: Int,
    val column: Int,
    val madeVisible: Boolean,
    val clamped: Boolean,
)

/**
 * Snapshot of an open editor returned by `editor.get_state`: caret(s), selection, viewport,
 * folding, inlay counts, and daemon gutter markers. Heavy sections are toggleable so the
 * caller can keep responses small.
 */
@Serializable
data class EditorState(
    val fileUrl: String,
    /** Primary caret first. Secondary carets appended when includeMultipleCarets=true. */
    val carets: List<CaretInfo>,
    /** Primary caret's selection. Null when no text is selected. */
    val selection: SelectionInfo? = null,
    /** 1-based logical-line range currently visible in the viewport. Null when the editor isn't laid out. */
    val visibleRange: LineRange? = null,
    /** Collapsed and expanded fold regions. Null when includeFolding=false. */
    val foldedRanges: List<FoldInfo>? = null,
    /** Inlay totals by kind. Null when includeInlays=false. */
    val inlayCounts: InlayCountSummary? = null,
    /**
     * Daemon-reported markers ≥ requested severity. Always non-null on success — empty list means
     * "daemon ran, no markers". Distinguish "daemon hasn't run yet" by re-querying after a delay.
     */
    val gutterMarkers: List<GutterMarkerInfo>? = null,
)

/**
 * One caret in the editor's CaretModel. The primary caret is reported first with
 * `isPrimary=true`; secondary carets carry their own selection range if any.
 */
@Serializable
data class CaretInfo(
    val offset: Int,
    val line: Int,
    val column: Int,
    val isPrimary: Boolean,
    val selectionStart: Int? = null,
    val selectionEnd: Int? = null,
)

/**
 * Primary caret's selection. `length == end - start`, `startLine`/`endLine` are 1-based.
 */
@Serializable
data class SelectionInfo(
    val start: Int,
    val end: Int,
    val startLine: Int,
    val endLine: Int,
    val length: Int,
)

/** Inclusive 1-based logical line range. */
@Serializable
data class LineRange(
    val startLine: Int,
    val endLine: Int,
)

/**
 * One fold region in the editor's FoldingModel. `expanded=false` means the user has collapsed
 * the region — the placeholder text is what's drawn in the gutter.
 */
@Serializable
data class FoldInfo(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val endLine: Int,
    val placeholder: String,
    val expanded: Boolean,
)

/**
 * Total inlay counts across the document, split by kind. `total == inline + block + afterLineEnd`.
 * Returning only counts (not the elements themselves) keeps the response small on heavily
 * annotated files where inlay enumeration can saturate the EDT.
 */
@Serializable
data class InlayCountSummary(
    val inline: Int,
    val block: Int,
    val afterLineEnd: Int,
    val total: Int,
)

/**
 * One daemon-produced highlight in the gutter / margins (errors, warnings, inspections).
 * Sourced from `DaemonCodeAnalyzerImpl.getHighlights(Document, HighlightSeverity, Project)`.
 *
 * `severity` is the IntelliJ severity name (ERROR / WARNING / WEAK_WARNING / INFORMATION / …).
 * `description` is the human message (`HighlightInfo.description`); `toolId` is the inspection
 * tool id when known (`HighlightInfo.inspectionToolId`).
 */
@Serializable
data class GutterMarkerInfo(
    val line: Int,
    val severity: String,
    val description: String? = null,
    val toolId: String? = null,
)
