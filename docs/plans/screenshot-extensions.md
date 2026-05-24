# `screenshot.highlight` + `screenshot.diff`

## Purpose & motivation

JetBrains' built-in MCP server (2025.2+) ships **zero** screenshot tools — this
group is fully our niche. Today's `ScreenshotToolset` (`capture` / `crop`) lets
an agent see the IDE but cannot point at a target ("where is the breadcrumb
bar?") or verify a visual change ("did Toggle Sidebar actually toggle?"). Two
helpers close that loop: `highlight` overlays a colored bbox around a known
componentId on a fresh capture; `diff` runs a pure-CPU pixel diff between two
caller-supplied base64 PNGs and returns a composite + stats + bbox.

**Success criteria**: (1) one MCP call `highlight(componentId=…)` produces a PNG
with a red rectangle around the Swing component, no client-side image work.
(2) `diff(before, after)` returns `differingPixels`, `diffPercentage`, and a
tight `bbox` so an agent can answer "did this action have a visible effect?"
without a multimodal round trip.

## Tool specification

### `screenshot.highlight`

```kotlin
@McpTool(name = "screenshot.highlight")
@McpDescription("""…see below…""")
suspend fun screenshot_highlight(
    @McpDescription("Stable id from a prior ui.find_by_* / ui.get_tree call. Resolved via ComponentRegistry — must still be attached.")
    componentId: String,
    @McpDescription("'component' | 'active_frame' | 'screen'. 'all_frames' is rejected (ambiguous coords).")
    target: String = "active_frame",
    @McpDescription("Box color as CSS hex ('#FF0000', '#F00') or named ('red', 'lime'). Default '#FF0000'. Invalid → red + warning.")
    color: String = "#FF0000",
    @McpDescription("Box stroke thickness in source-image pixels. Clamped to 1..20. Default 3.")
    thickness: Int = 3,
    @McpDescription("Optional label rendered just above the box (below if no headroom). UTF-8 truncated at 80 chars; newlines collapsed.")
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

Response = existing `ImagePayload`. Box geometry not serialized — caller supplied it.

### `screenshot.diff`

```kotlin
@McpTool(name = "screenshot.diff")
@McpDescription("""…see below…""")
suspend fun screenshot_diff(
    @McpDescription("Base64-encoded PNG of the 'before' state. Typically the base64 field of a prior screenshot.capture response.")
    before: String,
    @McpDescription("Base64-encoded PNG of the 'after' state.")
    after: String,
    @McpDescription("Per-channel tolerance (0..255). Default 8 — masks JBR anti-aliased text jitter. Clamped silently.")
    tolerance: Int = 8,
    @McpDescription("CSS hex / named color used to tint differing pixels. Default '#FF0000'.")
    highlightColor: String = "#FF0000",
    @McpDescription("0.0..1.0 alpha of the grayscale base before compositing. Default 0.4. Clamped silently.")
    baseTransparency: Float = 0.4f,
    @McpDescription("Size mismatch: 'resize' (bilinear scale after→before), 'pad' (top-left align, OOB transparent), 'error' (throw). Default 'resize'.")
    sizeMismatchPolicy: String = "resize",
): ImageDiffPayload
```

**`@McpDescription` draft** (verbatim):

```
|Pixel-diff two base64-encoded PNGs (typically a 'before' and 'after' from two
|prior screenshot.capture calls) and return a composite image highlighting changed
|pixels plus structured diff stats. Pure CPU — no EDT, no IDE state touched.
|
|The output is a desaturated, dimmed version of 'after' with differing pixels
|tinted in highlightColor. A bbox is computed for the smallest axis-aligned
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
|left align, faithful but inflates 'changed' regions), 'error' (reject).
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

Response model — new `model/ImageDiffPayload.kt`:

```kotlin
@Serializable data class BBox(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class ImageDiffPayload(
    val mimeType: String, val width: Int, val height: Int, val base64: String,
    val warnings: List<String> = emptyList(),
    val totalPixels: Int, val differingPixels: Int,
    val diffPercentage: Double,   // 0.0..100.0, 4 dp
    val bbox: BBox? = null,        // null when differingPixels == 0
)
```

`ImagePayload` unchanged; `ImageDiffPayload` parallels it — no inheritance
because `@Serializable` data classes don't cleanly extend across the serializer
boundary.

## IntelliJ APIs used

`ComponentRegistry.getInstance().lookup(id)`, `WindowManager.findVisibleFrame()`,
`McpExpectedError`, `Component.getLocationOnScreen()` (EDT-only, throws
`IllegalComponentStateException` when not displayable — wrap in EDT bounce +
`runCatching`). Pure AWT beyond that: `Graphics2D`, `BasicStroke`, `Color`,
`Font`, `BufferedImage`, `Robot`, `Rectangle`, `Base64`. No `@ApiStatus.Internal`.

## Threading, timeout, caching

`highlight` — base render uses `onEdtBlocking { … }` for capture AND
`getLocationOnScreen()` (Swing contract). The overlay (`drawRect` / `drawString`
on the returned `BufferedImage`) runs OFF EDT to keep the critical section
minimal: `val (base, origin, bounds, warns) = onEdtBlocking{…}; val annotated =
ScreenshotCapture.drawHighlight(base, bounds, color, …); return finalise(annotated, scale)`.

`diff` — pure CPU; no EDT / PSI / VFS. Decode → diff → encode on the ktor
coroutine.

**10 s cap** (CLAUDE.md) easily met: `highlight` ≈ existing `capture` cost + ~1
ms overlay (already bounded by `fitWithinBudget`'s 4-pass downscale). `diff` is
O(w × h): 4K (~8 MP) at ~20 ns/pixel ≈ 150 ms diff + ~200 ms encode = <500 ms
worst case. No caching — per-call data.

## Edge cases

`highlight`:

1. **componentId not in registry** — `McpExpectedError("Component '$id' is no
   longer attached")` (matches `capture target=component`).
2. **Not displayable** when `target ∈ {active_frame, screen}` —
   `getLocationOnScreen()` throws; catch → `McpExpectedError("Component is not
   currently visible — cannot compute frame/screen coords")`. `target=component`
   works on detached components via `paint()`.
3. **Bounds past frame** (offscreen popup, detached toolwindow) — clip to image
   bounds + warn `"component clipped to frame bounds"`; draw what's visible.
4. **Zero-area bounds** — draw small marker at origin + warn.
5. **`target="all_frames"`** — explicit reject; hint `active_frame` / `screen`.
6. **Color parse fail / label too long / newlines** — fall back to red + warn;
   UTF-8 truncate to 80 chars (existing helper), collapse `\n`/`\r` to spaces;
   place label inside-top of box if clipping the top edge.

`diff`:

7. **Base64 / `ImageIO.read` fail** — `McpExpectedError("'<which>' is not a
   valid PNG")`.
8. **Mismatched dimensions** — policy-driven: `resize` (default, HiDPI / window-
   resize case), `pad` (faithful but inflates diff), `error` (strict).
9. **AA text jitter** — `tolerance=8` default masks JBR HiDPI subpixel noise;
   validate in platform smoke (bump to 12 — see open Qs).
10. **Alpha channel diffed per-channel** — translucent→opaque counts even when
    RGB matches.
11. **Identical inputs** — `differingPixels=0`, `bbox=null`,
    `diffPercentage=0.0`; output is still a valid grayscale-of-after PNG.
12. **Output exceeds MCP budget** — same `fitWithinBudget` downscale; bbox is
    reported in the **returned (scaled)** image's coordinates + warn `"output
    downscaled by N halving passes; bbox is in scaled coordinates"`.
13. **Non-ARGB decoded type** (e.g. `TYPE_BYTE_INDEXED`) — convert once to
    `TYPE_INT_ARGB`, then diff via int-array fast path.

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

**Unit — `ImageDifferTest`**: identical 8×8 → no diff, null bbox; single-pixel
change → 1×1 bbox at that pixel; 3×3 block at (5,5) → bbox `{5,5,3,3}`;
`tolerance=10` masks +5 per-channel change, `tolerance=4` reports it; size
mismatch — `error` throws, `resize` returns resized stats, `pad` counts OOB
transparent-vs-opaque as diff; alpha-only change counts at `tolerance=0` not
`128`; highlight tint applied (sample changed-block center, channel > thresh).

**Unit — `ScreenshotHighlightTest`**: `drawHighlight` on 100×100 white, bounds
`(10,10,30,30)`, red, thickness 2 — pixel `(10,10)` red, `(11,11)` red,
`(50,50)` white; zero-size bounds draws marker; bounds clipped to edge does not
throw and emits warning; label placed above when headroom, inside-top when not.

**Unit — `ColorParsingTest`** (table): `#FF0000` / `#f00` / `#FF0000FF` → red;
`red` / `RED` case-insensitive → red; `lime` → `(0,255,0)`; `"not a color"` /
`#GGG` / `""` → null.

**Platform — `ScreenshotHighlightPlatformTest`** (extends `BasePlatformTestCase`):
register a `JButton` of known size in a parent JFrame; call
`screenshot_highlight target="component"`; decode PNG; assert stroke-color pixel
on the box edge. `target="active_frame"` on a real frame — verify size matches
and a colored stripe exists. `componentId="nonexistent"` → `McpExpectedError`.

`diff` is pure CPU — fully covered by units; no platform test needed.

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

1. **Multiple componentIds in one highlight call?** Useful for "show me A, B
   and C" but multiplies the color/label arg surface. **Decision v1: single
   component**; add `screenshot.highlight_many` later if demanded.
2. **Crop diff output to bbox before returning?** Shrinks responses for small
   changes but loses spatial context. **Decision: full-frame composite**; add
   `cropToBbox: Boolean = false` follow-up arg if budget pain appears.
3. **AA on the highlight rectangle.** `VALUE_ANTIALIAS_ON` looks cleaner at
   non-1.0 `scale` but fuzzes the stroke edges (complicates pixel-edge
   assertions). **Decision: AA on**; tests sample box interior, not stroke edge.
4. **Native screen DPI vs JBR HiDPI.** `target="screen"` (Robot) returns
   physical pixels; `target="active_frame"` returns logical pixels. Existing
   `capture` has this; we inherit and document if reported.
5. **Default `tolerance=8`** is a guess for JBR HiDPI subpixel jitter; validate
   in platform smoke and bump to `12` if a noop comparison exceeds 0.1% diff.

## References

- Existing: `tools/ScreenshotToolset.kt` (`screenshot_capture` render path +
  `finalise()`; `screenshot_crop` coord-space / clip math),
  `core/ScreenshotCapture.kt#fitWithinBudget`, `core/ComponentRegistry.kt#lookup`,
  `util/ImageEncoding.kt` (`encodePngBase64`, `scaleImage`),
  `util/EdtHelpers.kt#onEdtBlocking` (with `ModalityState.any()`).
- IntelliJ source: `WindowManager.findVisibleFrame` —
  https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/openapi/wm/WindowManager.java
- JetBrains MCP equivalent: **none**. The shipped JetBrains MCP server in
  IntelliJ 2025.2+ has zero screenshot tools — entire group is greenfield.
