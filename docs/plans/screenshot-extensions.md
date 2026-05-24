# `screenshot.highlight` + `screenshot.diff`

## Purpose & motivation

JetBrains' built-in MCP server in 2025.2+ exposes **zero** screenshot tools — this
group is fully our niche. The current `ScreenshotToolset` (`capture` / `crop`) lets
an agent see the IDE but cannot point at a target ("where is the breadcrumb bar?")
or verify a visual change ("did Toggle Sidebar actually toggle?"). Two helpers
close that loop: `highlight` overlays a colored bbox around a known componentId on
a fresh capture; `diff` does a pure-CPU pixel diff between two caller-supplied
base64 PNGs and returns the composite + stats + bbox.

**Success criteria**: (1) one MCP call `highlight(componentId=…)` produces a PNG
with a red rectangle around the Swing component, no client-side image work.
(2) `diff(before, after)` returns `differingPixels`, `diffPercentage`, and a tight
`bbox` so an agent can decide "did this action have a visible effect?" without a
multimodal round trip.

## Tool specification

### `screenshot.highlight`

**Signature:**

```kotlin
@McpTool(name = "screenshot.highlight")
@McpDescription("""…see below…""")
suspend fun screenshot_highlight(
    @McpDescription("Stable id from a prior ui.find_by_* / ui.get_tree call. Resolved via ComponentRegistry — must still be attached.")
    componentId: String,
    @McpDescription("'component' | 'active_frame' | 'screen'. 'all_frames' is rejected (ambiguous coords).")
    target: String = "active_frame",
    @McpDescription("Box color as CSS hex ('#FF0000', '#F00') or named ('red', 'lime'). Default '#FF0000'. Invalid → red.")
    color: String = "#FF0000",
    @McpDescription("Box stroke thickness in source-image pixels. Clamped to 1..20. Default 3.")
    thickness: Int = 3,
    @McpDescription("Optional label rendered just above the box (below if no headroom). UTF-8 truncated at 80 chars.")
    label: String? = null,
    @McpDescription("Post-render scale applied AFTER the highlight is drawn, so the stroke scales with it.")
    scale: Double = 1.0,
    @McpDescription("Image format. Only 'png' in v1.")
    format: String = "png",
): ImagePayload
```

**`@McpDescription` draft** (verbatim — trim-margin form):

```
|Captures a screenshot of the IDE (target='component'|'active_frame'|'screen') AND
|overlays a colored bounding rectangle around the Swing component identified by
|componentId, optionally with a text label. Returns the same base64-PNG
|ImagePayload as screenshot.capture, downscaled to the MCP response budget.
|
|target options (must match the coordinate space the highlight is drawn in):
|  - "component"    — render only the target component; the box fills it. Cheapest.
|  - "active_frame" — render the focused IDE frame, box at frame-relative bounds
|                     (Component.getLocationOnScreen minus frame origin). Off-screen
|                     components are clipped to the frame edge with a warning.
|  - "screen"       — Robot capture of the virtual desktop with the box at the
|                     component's absolute screen coordinates. The only target that
|                     includes popups / tooltips / floating overlays.
|
|Use this when: the user asks "where is X on screen?", or after a ui.find_by_*
|call you want to visually confirm which component matched, or to annotate a
|screenshot for screenshot-and-narrate workflows.
|
|Do NOT use this when: you just want raw pixels (use screenshot.capture), you want
|a crop centered on the component (use screenshot.crop after ui.get_properties), or
|you need to highlight multiple components in one image (not supported in v1).
|
|Returns: { mimeType:"image/png", width:int, height:int, base64:string,
|warnings:string[] } — same shape as screenshot.capture. warnings includes
|"component clipped to frame", "color parse fell back to red", and the standard
|"image downscaled to fit budget" notices.
|
|Examples:
|  componentId="c_a3f2e1b8"                                    — red box on active frame
|  componentId="c_a3f2e1b8", color="#33CC33", thickness=5      — fatter green box
|  componentId="c_a3f2e1b8", target="screen", label="OK btn"   — labelled, includes popups
|  componentId="c_a3f2e1b8", target="component", scale=0.5     — component-only, halved
```

**Args**: see signature. Notable validation: `target` "all_frames" is rejected;
`thickness` clamped `1..20`; `label` trimmed to 80 UTF-8 bytes via existing
`Utf8Truncation`, with `\n`/`\r` collapsed to spaces; `color` parse failure falls
back to red and adds a warning.

**Response**: existing `ImagePayload`. Box geometry intentionally not serialized —
the caller supplied it.

### `screenshot.diff`

**Signature:**

```kotlin
@McpTool(name = "screenshot.diff")
@McpDescription("""…see below…""")
suspend fun screenshot_diff(
    @McpDescription("Base64-encoded PNG of the 'before' state. Typically the base64 field of a prior screenshot.capture response.")
    before: String,
    @McpDescription("Base64-encoded PNG of the 'after' state.")
    after: String,
    @McpDescription("Per-channel tolerance (0..255). Default 8 — masks JBR anti-aliased text jitter.")
    tolerance: Int = 8,
    @McpDescription("CSS hex / named color used to tint differing pixels. Default '#FF0000'.")
    highlightColor: String = "#FF0000",
    @McpDescription("0.0..1.0 alpha of the grayscale base before compositing. Default 0.4.")
    baseTransparency: Float = 0.4f,
    @McpDescription("Size mismatch: 'resize' (bilinear scale after→before), 'pad' (top-left align, OOB treated as transparent), 'error' (throw). Default 'resize'.")
    sizeMismatchPolicy: String = "resize",
): ImageDiffPayload
```

**`@McpDescription` draft** (verbatim):

```
|Pixel-diff two base64-encoded PNGs (typically a 'before' and 'after' from two
|prior screenshot.capture calls) and return a composite image highlighting changed
|pixels plus structured diff stats. Pure CPU — no EDT, no IDE state touched.
|
|The output image is a desaturated, dimmed version of 'after' with differing
|pixels tinted in highlightColor. A bbox is computed for the smallest axis-aligned
|rectangle containing every differing pixel (null when no pixels differ).
|
|Use this when: an agent needs to verify a UI change had a visible effect ("did
|Toggle Sidebar actually toggle it?"), localize the changed region of a screen
|before zooming in, or compute a quick "% changed" sanity number before spending
|tokens on a vision pass.
|
|Do NOT use this when: you want a fresh screenshot (use screenshot.capture), you
|want to highlight a known component (use screenshot.highlight — no diff needed),
|or you want OCR / semantic comparison (this is pixel math, not vision).
|
|tolerance is per-channel (R/G/B/A independently). 0 = exact match required; 8
|(default) absorbs subpixel-rendering jitter and JBR HiDPI antialiasing noise.
|sizeMismatchPolicy: 'resize' (default, safe for HiDPI/window-resize), 'pad' (top-
|left align, faithful but produces large 'changed' regions), 'error' (reject).
|
|Returns: { mimeType:"image/png", width, height, base64, warnings:string[],
|totalPixels:int, differingPixels:int, diffPercentage:double,
|bbox:{x,y,width,height}? }.
|
|Examples:
|  before=<b64>, after=<b64>                                    — defaults
|  before=<b64>, after=<b64>, tolerance=0                       — exact match
|  before=<b64>, after=<b64>, sizeMismatchPolicy="error"        — strict sizes
|  before=<b64>, after=<b64>, highlightColor="#FFFF00",
|    baseTransparency=0.2f                                      — yellow on dark
```

**Args**: see signature. `tolerance` clamped `0..255`; `baseTransparency` clamped
`0.0..1.0`; bad base64 / non-PNG → `McpExpectedError("'before' is not a valid
PNG …")`. Default `sizeMismatchPolicy="resize"` because HiDPI / window resize
causes near-identical screenshots to differ in dimensions and we want diff to
"just work" for the common case.

**Response model** — new `model/ImageDiffPayload.kt`:

```kotlin
@Serializable data class BBox(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class ImageDiffPayload(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val base64: String,
    val warnings: List<String> = emptyList(),
    val totalPixels: Int,
    val differingPixels: Int,
    val diffPercentage: Double,   // 0.0..100.0, 4 dp
    val bbox: BBox? = null,        // null when differingPixels == 0
)
```

`ImagePayload` (existing) stays unchanged; `ImageDiffPayload` parallels it — no
inheritance because `@Serializable` data classes don't cleanly extend across the
serializer boundary.

## IntelliJ APIs used

- `ComponentRegistry.getInstance().lookup(id)` — existing.
- `WindowManager.findVisibleFrame()` — existing usage in `ScreenshotToolset`.
- `com.intellij.mcpserver.McpExpectedError` — existing.
- `Component.getLocationOnScreen()` (JDK) — EDT-only; throws
  `IllegalComponentStateException` if not displayable. Wrap with EDT bounce +
  `runCatching`.
- AWT only: `Graphics2D`, `BasicStroke`, `Color`, `Font`, `BufferedImage`,
  `Robot`, `Rectangle`, `Base64`. No `@ApiStatus.Internal` surface.

## Threading & EDT model

**`screenshot.highlight`** — base render path identical to `screenshot.capture`:
`onEdtBlocking { … }` for the capture call AND for `getLocationOnScreen()` (Swing
contract). The highlight draw (`drawRect` / `drawString` on the returned
`BufferedImage`) is pure pixel work on a heap-resident image — runs OFF EDT to
keep the EDT critical section minimal:

```
val (base, screenOrigin, compBounds, warns) = onEdtBlocking { … }
val annotated = ScreenshotCapture.drawHighlight(base, compBounds, color, …)
return finalise(annotated, scale)
```

**`screenshot.diff`** — pure CPU. No EDT, no PSI, no VFS. Decode → diff → encode
all on the ktor coroutine.

Neither tool caches (per-call data).

## Timeout strategy

Hard 10 s cap per CLAUDE.md.

- `highlight`: dominated by `screenshot.capture` cost. Adds one `drawRect` +
  optional `drawString` (~1 ms), no extra EDT bounce beyond capture's.
  `ScreenshotCapture.fitWithinBudget`'s 4-attempt downscale already bounds total
  work.
- `diff`: O(width × height) per-channel diff. 4K (≈8 MP) at ~20 ns/pixel ARGB =
  ~150 ms; encode adds ~200 ms; worst case <500 ms. Same `fitWithinBudget` cap on
  the output.

If a future capture path adds an unbounded op, wrap the body in
`withTimeoutOrNull(10_000)`.

## Edge cases

`screenshot.highlight`:

1. **componentId not in registry** — `McpExpectedError("Component '$id' is no
   longer attached")` (matches `capture target=component`).
2. **Component not displayable** when `target ∈ {active_frame, screen}` —
   `getLocationOnScreen()` throws; catch and throw
   `McpExpectedError("Component is not currently visible — cannot compute
   frame/screen coordinates")`. For `target=component`, `paint()` works on
   detached components, so this isn't an issue.
3. **Component bounds extend past the frame** (offscreen popup, detached
   toolwindow) — clip the box to image bounds, warn
   `"component clipped to frame bounds"`. Still draw what's visible.
4. **Zero-area bounds** — draw a small marker at the origin, warn
   `"component has zero-size bounds; drew a marker"`.
5. **`target="all_frames"`** — explicitly rejected (ambiguous which frame). Hint
   the caller toward `active_frame` or `screen`.
6. **Color parse failure** — fall back to red + warning; do not throw.
7. **Label too long / newlines** — UTF-8 truncate to 80 chars (existing helper),
   collapse `\n`/`\r` to spaces, draw single line.
8. **Label clips top edge** — place inside-top of the box instead of above.

`screenshot.diff`:

9. **Base64 decode or `ImageIO.read` fails** — `McpExpectedError("'<which>' is
   not a valid PNG")`.
10. **Mismatched dimensions** — policy-driven (`resize` default / `pad` /
    `error`). `resize` covers the dominant HiDPI / window-resize case;
    `pad` is faithful but inflates `differingPixels`; `error` is strict.
11. **Anti-aliased text jitter** — default `tolerance=8` documented to mask JBR
    HiDPI subpixel jitter; validated in platform smoke.
12. **Alpha channel** — diffed per-channel: translucent→opaque counts as
    different even when RGB matches.
13. **Identical inputs** — `differingPixels=0`, `bbox=null`,
    `diffPercentage=0.0`; output image is still a valid grayscale-of-after PNG.
14. **Output exceeds MCP budget** — same `fitWithinBudget` downscale; bbox is
    reported in the **returned (scaled)** image's coordinates, with a warning
    `"output downscaled by N halving passes; bbox is in scaled coordinates"`.
15. **Non-ARGB decoded type** (e.g. `TYPE_BYTE_INDEXED`) — convert to
    `TYPE_INT_ARGB` once, then diff via the int-array fast path.
16. **`tolerance` / `baseTransparency` out of range** — clamp silently.

## Files to create/modify

| Path | Op | What |
|------|----|------|
| `tools/ScreenshotToolset.kt` | Edit | Add `screenshot_highlight` + `screenshot_diff` `@McpTool` methods; reuse `finalise()` |
| `core/ScreenshotCapture.kt` | Edit | Add `drawHighlight(base, bounds, color, thickness, label)` — pure, off-EDT, testable |
| `core/ImageDiffer.kt` | Create | Headless `diff(before, after, tolerance, color, baseAlpha, policy): DiffResult` — pure JVM |
| `model/ImageDiffPayload.kt` | Create | `@Serializable` `BBox` + `ImageDiffPayload` (parallels `ImagePayload`, no inheritance) |
| `model/args/ScreenshotArgs.kt` | Create | `@Serializable` arg mirrors (consistency with other plans; used by tests) |
| `util/ColorParsing.kt` | Create | `parseCssColor(raw): Color?` — hex (`#RGB`/`#RRGGBB`/`#RRGGBBAA`) + named-color table |
| `src/test/kotlin/.../core/ImageDifferTest.kt` | Create | Unit tests for diff math, bbox, tolerance, size-mismatch policies |
| `src/test/kotlin/.../core/ScreenshotHighlightTest.kt` | Create | Unit tests for `drawHighlight` (synthetic image, pixel assertions) |
| `src/test/kotlin/.../util/ColorParsingTest.kt` | Create | Table-driven parser tests |
| `src/test/kotlin/.../core/platform/ScreenshotHighlightPlatformTest.kt` | Create | `BasePlatformTestCase` smoke — register `JButton`, call toolset, decode PNG, assert stroke pixel |

No XML wiring — both tools live in the existing `ScreenshotToolset` already
registered by `META-INF/mcp-integration.xml`.

## Test plan

**Unit — `ImageDifferTest.kt`** (pure JVM):

- identical 8×8 ARGB → `differingPixels=0`, `bbox=null`, `diffPercentage=0.0`.
- single pixel changed → `differingPixels=1`, bbox is 1×1 at that pixel.
- 3×3 block changed at (5,5) in a 32×32 image → bbox `{5,5,3,3}`.
- `tolerance=10` masks a +5 per-channel change; `tolerance=4` reports it.
- `policy="error"` throws on size mismatch; `"resize"` succeeds & returns
  resized stats; `"pad"` succeeds & counts OOB transparent vs opaque as diff.
- alpha-only change (255→128) counts at `tolerance=0`, not at `tolerance=128`.
- highlight tint actually applied (sample changed-block center, channel > thresh).

**Unit — `ScreenshotHighlightTest.kt`** (pure JVM):

- `drawHighlight` on 100×100 white, bounds `(10,10,30,30)`, red, thickness 2 —
  pixel `(10,10)` red, `(11,11)` red, `(50,50)` white.
- zero-size bounds draws marker (not no-op).
- bounds clipped to image edge does not throw; emits warning.
- label placed above when there's headroom; inside-top when not.

**Unit — `ColorParsingTest.kt`** (table-driven):

- `#FF0000` / `#f00` / `#FF0000FF` → red.
- `red` / `RED` (case-insensitive) → red.
- `lime` → `(0,255,0)`.
- `not a color` / `#GGG` / `""` → null.

**Platform — `ScreenshotHighlightPlatformTest.kt`** (extends `BasePlatformTestCase`):

- register a `JButton` of known size in a parent JFrame; call
  `screenshot_highlight target="component"`; decode PNG; assert stroke-color
  pixel exists at the box edge.
- `target="active_frame"` on a real frame; verify returned size matches frame
  and includes a colored stripe.
- `componentId="nonexistent"` → `McpExpectedError`.

`screenshot.diff` doesn't need a platform test — pure CPU, fully covered by
units.

## Estimated effort

| Step | Hours |
|------|-------|
| `util/ColorParsing.kt` + tests | 1.0 |
| `ScreenshotCapture.drawHighlight` + unit tests | 1.5 |
| `core/ImageDiffer.kt` + unit tests | 2.5 |
| `model/ImageDiffPayload.kt` + args | 0.5 |
| Two `@McpTool` methods + descriptions | 1.5 |
| Platform test (sandbox tick) | 1.0 |
| Doc-gen verify + manual smoke | 0.5 |
| **Total** | **~1 day combined** |

## Open questions / risks

1. **Multiple componentIds in one highlight call?** Useful for "show me A, B and
   C" but multiplies the color/label arg surface. **Decision v1: single
   component**; add `screenshot.highlight_many` later if demanded.
2. **Crop diff output to bbox before returning?** Shrinks responses for small
   changes but loses spatial context. **Decision: return full-frame composite**;
   add `cropToBbox: Boolean = false` follow-up arg if budget pain appears.
3. **AA on the highlight rectangle.** `VALUE_ANTIALIAS_ON` looks cleaner at
   non-1.0 `scale` but fuzzes the stroke edges (complicates pixel-edge
   assertions). **Decision: AA on**; tests sample box interior, not stroke edge.
4. **Native screen DPI vs JBR HiDPI.** `target="screen"` (Robot) returns physical
   pixels; `target="active_frame"` returns logical pixels. Existing `capture`
   already has this; we inherit and document if reported.
5. **Default `tolerance=8`** is a guess for JBR HiDPI subpixel jitter on
   Linux/macOS. Validate during platform smoke; bump to `12` if
   "before vs immediately-after-noop" exceeds 0.1% diff.

## References

- Existing code:
  - `tools/ScreenshotToolset.kt#screenshot_capture` — base render path,
    `finalise()`, error pattern.
  - `tools/ScreenshotToolset.kt#screenshot_crop` — coord-space translation,
    clip math.
  - `core/ScreenshotCapture.kt#fitWithinBudget` — downscale loop reused by both.
  - `core/ComponentRegistry.kt#lookup` — id → Component.
  - `util/ImageEncoding.kt` — `encodePngBase64`, `scaleImage`.
  - `util/EdtHelpers.kt#onEdtBlocking` — EDT bounce with `ModalityState.any()`.
- IntelliJ source: `WindowManager.findVisibleFrame` —
  https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/openapi/wm/WindowManager.java
- JetBrains MCP equivalent: **none**. Greenfield — JetBrains' shipped MCP server
  in IntelliJ 2025.2+ has zero screenshot tools.
