# `editor.*` group — `editor.set_caret` + `editor.get_state`

## Purpose & motivation

JetBrains' built-in MCP server (IntelliJ 2025.2+) exposes file I/O
(`open_file_in_editor`, `replace_text_in_file`) but offers **no caret control** and
**no full editor-state read**: an agent can put a file on the screen but cannot move
the cursor, see selection ranges, inspect folded regions, count gutter markers, or
know what's actually visible in the viewport. `psi.list_open_files` only reports a
single `caretOffset`. A **new** group (rather than extending `psi.*`, which is
read-only and PSI-tree-centric) is the right home: caret mutation is a write, and
viewport/folding/inlay/gutter data are Swing-editor concerns unrelated to PSI.

We deliberately do NOT add (JetBrains already covers): `editor.open_file`
(`open_file_in_editor`), `editor.replace_text` (`replace_text_in_file`),
`editor.close_file` (`close_file`), `editor.get_text` (`get_file_text_by_path`).

Success criterion: an agent can read the active file, jump to a specific line+column,
scroll it into view, then re-query `get_state` to confirm caret position, selection,
and visible gutter markers — without a screenshot round-trip.

## Tool specifications

### `editor.set_caret`

```kotlin
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
    |Split view: targets `FileEditorManager.selectedEditor` for that file — the
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
): SetCaretResponse
```

### `editor.get_state`

```kotlin
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
): EditorState
```

## Args + response models

`model/args/EditorArgs.kt` — `SetCaretArgs` / `GetStateArgs` mirror the tool
signatures; both `@Serializable`.

`model/EditorInfo.kt`:

```kotlin
@Serializable data class SetCaretResponse(
    val ok: Boolean, val fileUrl: String,
    val oldOffset: Int, val newOffset: Int,
    val line: Int, val column: Int,             // 1-based, post-clamp
    val madeVisible: Boolean, val clamped: Boolean,
)
@Serializable data class EditorState(
    val fileUrl: String,
    val carets: List<CaretInfo>,                 // primary first
    val selection: SelectionInfo?,               // null = no selection
    val visibleRange: LineRange?,                // 1-based logical lines
    val foldedRanges: List<FoldInfo>?,           // null when includeFolding=false
    val inlayCounts: InlayCountSummary?,         // null when includeInlays=false
    val gutterMarkers: List<GutterMarkerInfo>?,
)
@Serializable data class CaretInfo(
    val offset: Int, val line: Int, val column: Int, val isPrimary: Boolean,
    val selectionStart: Int? = null, val selectionEnd: Int? = null,
)
@Serializable data class SelectionInfo(
    val start: Int, val end: Int, val startLine: Int, val endLine: Int, val length: Int,
)
@Serializable data class LineRange(val startLine: Int, val endLine: Int)
@Serializable data class FoldInfo(
    val startOffset: Int, val endOffset: Int, val startLine: Int, val endLine: Int,
    val placeholder: String, val expanded: Boolean,
)
@Serializable data class InlayCountSummary(
    val inline: Int, val block: Int, val afterLineEnd: Int, val total: Int,
)
@Serializable data class GutterMarkerInfo(
    val line: Int, val severity: String, val description: String?, val toolId: String?,
)
```

## IntelliJ APIs used

- `com.intellij.openapi.fileEditor.FileEditorManager` — `selectedTextEditor`,
  `selectedEditor`, `getEditors(VirtualFile)`.
- `com.intellij.openapi.editor.Editor` — `caretModel.{primaryCaret, allCarets,
  moveToLogicalPosition, moveToOffset, offset}`, `selectionModel.*`,
  `scrollingModel.{scrollToCaret(ScrollType.MAKE_VISIBLE), visibleAreaOnScrollingFinished}`,
  `foldingModel.allFoldRegions`, `inlayModel.get{Inline,Block,AfterLineEnd}ElementsInRange`.
- `com.intellij.openapi.editor.LogicalPosition` for line+column → offset.
- `com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl.getHighlights(
  Document, HighlightSeverity, Project)` — `@ApiStatus.Internal`; same path the
  bundled Problems view uses. Fallback:
  `MarkupModelEx.processRangeHighlightersOverlappingWith` filtered by daemon-owned
  tooltip renderer.
- `com.intellij.lang.annotation.HighlightSeverity`.

## Threading & EDT model

- Both tools touch a Swing `Editor` → run inside `onEdtBlocking { … }`
  (uses `ModalityState.any()`, 10 s cap from `EdtHelpers`).
- Inside the EDT block, wrap daemon / document / folding reads in
  `ReadAction.compute<T, RuntimeException> { … }` (EDT does not imply read-action
  ownership).
- `editor.set_caret` writes to the caret model — also EDT-only, same bounce.
- No PSI parsing required.

## Timeout strategy

Hard 10 s cap. Both tools complete in <100 ms on a normal file: caret math is O(1),
fold/inlay/caret enumeration is O(N) over typically <100 elements, daemon
`getHighlights` is an in-memory list lookup. The one risk is `includeInlays=true` on
a heavily annotated file — we mitigate by returning *only counts* (not the elements
themselves) and leaving `includeInlays` off by default.

## Edge cases

1. **File not open.** Both tools fail fast with `McpExpectedError("File not open:
   <url>. Call open_file_in_editor first.")` — keeps the tools minimal and avoids
   duplicating JetBrains' `open_file_in_editor` semantics.
2. **`fileUrl=null` and no active tab.** Same error shape as `psi.*`:
   `"No active editor tab. Open a file first, or pass fileUrl from psi.list_open_files."`
3. **Line/column or offset past EOF.** Clamp to last line / `document.textLength`,
   set `clamped=true`.
4. **Both `offset` and `line`+`column` passed.** `offset` wins (documented).
5. **Multiple editors per file (split view).** Use `FileEditorManager.selectedEditor`
   for that file — most recently focused split. Per-split addressing is v2.
6. **Binary file in editor (image viewer, Hex Editor).** `selectedTextEditor` is null;
   fail with `"File is not a text editor: <url> (file type=<X>)"`.
7. **Daemon hasn't run (file just opened, indexing).** `gutterMarkers` returns an
   empty list — not null — so the caller distinguishes "no markers" from "couldn't
   query".
8. **Dumb mode.** Daemon highlights are limited but available; no `DumbService` gate.
   Folding and caret are dumb-safe.
9. **Secondary carets with own selection.** Each `CaretInfo` carries its own
   `selectionStart/End`; top-level `selection` reflects the primary caret only.
10. **Folding lazy on first paint.** `allFoldRegions` can be empty on a never-shown
    file — acceptable.

## META-INF wiring

`editor.*` works in **any** IDE (no Java module / Kotlin plugin dependency). Add one
`<mcpServer.mcpToolset>` line to `src/main/resources/META-INF/mcp-integration.xml`
alongside the other always-on toolsets. No new shim XML.

## Files to create/modify

| Path | Op | What |
|------|----|------|
| `src/main/kotlin/.../tools/EditorToolset.kt` | Create | `@McpTool` methods, EDT bounce, arg validation. |
| `src/main/kotlin/.../core/EditorStateInspector.kt` | Create | Headless logic: editor resolution, caret math, folding/inlay/daemon collection. |
| `src/main/kotlin/.../model/EditorInfo.kt` | Create | All response data classes. |
| `src/main/kotlin/.../model/args/EditorArgs.kt` | Create | `SetCaretArgs`, `GetStateArgs`. |
| `src/main/resources/META-INF/mcp-integration.xml` | Edit | Register `EditorToolset`. |
| `src/test/kotlin/.../core/EditorStateInspectorTest.kt` | Create | Unit tests. |
| `src/test/kotlin/.../core/platform/EditorStateInspectorPlatformTest.kt` | Create | Platform tests against real `EditorImpl`. |

## Test plan

**Unit** (no IntelliJ runtime):
- `gutterMinSeverity` parser: "ERROR" → HighlightSeverity.ERROR, "ALL" →
  INFORMATION floor, invalid → IllegalArgumentException.
- Line/column clamp helper: out-of-range clamps + sets `clamped=true`.
- Inlay-count aggregation: `total == inline + block + afterLineEnd`.

**Platform** (extends `BasePlatformTestCase`, fixtures under
`src/test/testData/editor/`):
1. `set_caret` happy path — `(line=3, column=14)` on `Sample.java` → assert
   `caretModel.logicalPosition == (2,13)`, `oldOffset != newOffset`, `clamped=false`.
2. `set_caret` clamp — `(line=9999, column=1)` → `clamped=true`, caret at last line.
3. `set_caret` file-not-open — expect `McpExpectedError("File not open: …")`.
4. `get_state` after `set_caret` — move to (5,1) → `carets[0].line == 5`,
   `selection == null`, `visibleRange` brackets line 5.
5. `get_state` with selection — `selectionModel.setSelection(…)` →
   `selection.length` matches.
6. `get_state` with folding — add a fold via `runBatchFoldingOperation` →
   `foldedRanges` contains it with `expanded=false`.
7. `get_state` gutter markers — fixture with `int x = "bad";`, restart daemon +
   `UIUtil.dispatchAllInvocationEvents()` (≤5 s), expect ≥1 entry with
   `severity="ERROR"`.
8. Binary-file rejection — open an image, expect
   `McpExpectedError("File is not a text editor…")`.

## Estimated effort

~1 day combined: models + args ~1 h; `EditorStateInspector` (caret math, fold/inlay,
daemon read) ~3 h; `EditorToolset` wrappers + EDT bouncing + arg validation ~1.5 h;
META-INF wiring + `runIde` smoke ~30 min; unit tests ~1 h; platform tests (daemon
timing is fiddly) ~2 h.

## Open questions / risks

1. **`set_caret` accepts `offset` as well as line+column?** **Yes** — matches `psi.*`
   precedent. Already in the signature.
2. **`get_state` returns the current line's text content?** Recommend **yes, but
   guarded** — add `includeCurrentLineText: Boolean = false` in v1.1; trivial
   (`document.getText(lineStartOffset..lineEndOffset)`). Skip in v1 to keep responses
   small and avoid duplicating text the agent likely has from `psi.get_structure`.
3. **`editor.scroll_to(line)` as a third tool?** Cheap, but
   `set_caret(scrollToVisible=true)` covers the common case. Defer until requested.
4. **`DaemonCodeAnalyzerImpl` is `@ApiStatus.Internal`.** If it breaks, swap to
   `MarkupModelEx.processRangeHighlightersOverlappingWith` filtered by daemon-owned
   tooltip renderer. Document the fallback in the Inspector.
5. **Split-view targeting (v2).** Add optional `splitIndex: Int?` later if users
   need it — would require `FileEditorManagerEx.windows` enumeration.
6. **"Reversible" wording.** `set_caret` is not Ctrl-Z-reversible (caret moves
   aren't undoable in IntelliJ); means "the caller can restore via a second tool
   call using `oldOffset`".

## References

- `PsiToolset.caretOffsetOf` / `PsiToolset.resolveOffset` in
  `src/main/kotlin/.../tools/PsiToolset.kt` — same EDT-bounce + offset-resolution.
- `EdtHelpers.onEdtBlocking` in `src/main/kotlin/.../util/EdtHelpers.kt`.
- IntelliJ Community: `platform/platform-impl/src/com/intellij/openapi/editor/impl/CaretModelImpl.kt`,
  `platform/analysis-impl/src/com/intellij/codeInsight/daemon/impl/DaemonCodeAnalyzerImpl.java`.
- JetBrains built-in MCP: `open_file_in_editor`, `replace_text_in_file`,
  `get_open_in_editor_file_text` — none expose caret or folding/inlay/gutter state.
