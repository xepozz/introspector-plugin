# `psi.symbol_at` + `psi.get_outline`

## Purpose & motivation

Two "cheap navigation" tools filling the gap between leaf-level `psi.get_structure`
(5 000-node AST dumps) and the heavy `psi.find_usages`. Today, answering "what's
under the cursor?" or "what methods are in this file?" requires walking the whole
PSI tree.

`psi.symbol_at` returns ONE compact symbol description for a position, with
explicit reference-vs-declaration disambiguation. `psi.get_outline` returns the
declaration tree (bodies omitted), about an order of magnitude cheaper than the
full structure dump. Both complement JetBrains' built-in `get_symbol_info`
(coarser taxonomy, no `isReference`) and `generate_psi_tree` (takes raw code,
not a file).

**Success criterion**: an agent answers "what symbol is at line 42?" / "list
methods of `MyService.kt`" in ONE call returning <100 fields, without fetching
the 5 000-node `psi.get_structure` response.

## Tool specification

### `psi.symbol_at`

```kotlin
@McpTool(name = "psi.symbol_at")
@McpDescription("""…see below…""")
suspend fun psi_symbol_at(
    @McpDescription("VFS URL of the file. null → active editor tab.") fileUrl: String? = null,
    @McpDescription("Document offset. Alternative to line+column.") offset: Int? = null,
    @McpDescription("1-based line. Alternative to `offset`.") line: Int? = null,
    @McpDescription("1-based column. Alternative to `offset`.") column: Int? = null,
    @McpDescription("Include KDoc / JavaDoc text. Default true.") includeDoc: Boolean = true,
    @McpDescription("Max chars of doc text. Longer is suffixed with '…'. Default 400.") truncateDocAt: Int = 400,
): SymbolAtResponse
```

**`@McpDescription` (verbatim, trim-margin):**

```
|Returns ONE compact description of the symbol at a given position. Cheap one-shot:
|no full PSI walk, no project-wide search. Equivalent to JetBrains' `get_symbol_info`
|but with a richer kind taxonomy and explicit reference-vs-declaration disambiguation.
|
|Use this when:
|  - The user asks "what is this thing?" / "what's under the cursor?".
|  - You want a single FQN before calling psi.find_usages or code.get_class_source.
|  - You need to disambiguate "cursor on a usage vs. on the declaration itself" —
|    `isReference` answers this; when true, name/kind/fqn describe the resolved DECLARATION.
|
|Do NOT use this when:
|  - You need every reference in the file (use psi.get_references with scope="file").
|  - You want the full PSI subtree at this position (use psi.get_structure).
|  - You want all declarations in the file (use psi.get_outline).
|
|Position: pass `offset` OR `line`+`column` (1-based).
|
|Returns: SymbolAtResponse { fileUrl, offset, position {line,column}, symbol: SymbolInfo? }.
|SymbolInfo carries:
|  - name                — simple name; null for anonymous
|  - kind                — class | interface | enum | annotation | record | object |
|                          companion | method | constructor | field | property |
|                          parameter | variable | typeAlias | enumConstant | import |
|                          label | unknown
|  - fqn                 — FQN for top-level / member declarations; null for locals
|  - psiClass            — simple PSI class name ("KtNamedFunction", "PsiMethod")
|  - declarationRange    — absolute range of the DECLARATION in its file
|  - declarationFileUrl  — VFS URL of the declaration's file (may differ from request
|                          fileUrl when isReference=true — e.g. a jar:// URL)
|  - containingDeclarationName — enclosing method/class/file name for context
|  - modifiers           — PSI modifier set (public/protected/private/static/final/...)
|  - returnType          — only for method/constructor
|  - typeText            — only for field/property/variable/parameter
|  - isReference         — true if cursor is on a USAGE; name/kind/fqn describe the
|                          resolved declaration. false if cursor is on the declaration itself.
|  - docText             — KDoc / JavaDoc snippet, truncated. Null when includeDoc=false
|                          or no doc present.
|
|When the position resolves to nothing (whitespace, comment, EOF, binary file):
|symbol = null with a warning.
|
|Examples:
|  fileUrl=null, line=12, column=8     — symbol under caret at row 12 col 8 of active tab
|  fileUrl="file:///…/Foo.kt", offset=420
|  includeDoc=false                    — skip KDoc lookup for speed
```

**Args**: same `resolveOffset` helper already in `PsiToolset`; `truncateDocAt`
∈ 0..4096.

### `psi.get_outline`

```kotlin
@McpTool(name = "psi.get_outline")
@McpDescription("""…see below…""")
suspend fun psi_get_outline(
    @McpDescription("VFS URL of the file. null → active editor tab.") fileUrl: String? = null,
    @McpDescription("Include fields/properties. Default true.") includeFields: Boolean = true,
    @McpDescription("Include inherited members. Default false.") includeInherited: Boolean = false,
    @McpDescription("Max outline depth. Default 6.") maxDepth: Int = 6,
    @McpDescription("Hard cap on outline nodes. Default 500.") maxNodes: Int = 500,
): GetOutlineResponse
```

**`@McpDescription` (verbatim, trim-margin):**

```
|Returns the Structure View / Outline of a file — only top-level and nested declarations
|(classes, interfaces, methods, fields, properties, top-level functions) as a tree. Skips
|bodies, statements, expressions, comments — about an order of magnitude cheaper than
|psi.get_structure. Matches what the Structure tool window displays.
|
|Use this when:
|  - The user asks "what methods are in this file?" / "show me the structure of foo.kt".
|  - You want a navigable index of declarations before drilling in with
|    code.get_class_source or psi.symbol_at.
|  - You need a per-language outline that respects language structure-view contributions.
|
|Do NOT use this when:
|  - You need the AST including expressions / tokens (use psi.get_structure).
|  - You need one specific symbol (use psi.symbol_at).
|  - The file is binary / plain text without a structure view — the response will be
|    empty with a warning.
|
|Backed by IntelliJ's `StructureViewBuilder` / `StructureViewModel` — the same per-language
|extension that powers the Structure tool window. Each language plugin contributes its
|own treeBuilder.
|
|Returns: GetOutlineResponse { fileUrl, fileType, language, nodes: OutlineNode[], nodeCount,
|truncated, warnings }. Each OutlineNode carries name, kind (same taxonomy as
|psi.symbol_at), fqn, psiClass, declarationRange, modifiers, returnType (methods),
|typeText (fields/properties), children[].
|
|Cost: O(declarations). Capped at maxNodes (default 500).
|
|Examples:
|  fileUrl=null                              — outline of active tab
|  fileUrl="file:///…/Foo.kt"                — explicit file
|  includeFields=false                       — methods-only outline
|  includeInherited=true                     — fold in superclass members
```

## Response models (append to `model/PsiInfo.kt`)

```kotlin
@Serializable data class LineColumn(val line: Int, val column: Int)

@Serializable
data class SymbolInfo(
    val name: String?, val kind: String, val fqn: String? = null,
    val psiClass: String, val declarationRange: TextRangeInfo,
    val declarationFileUrl: String,
    val containingDeclarationName: String? = null,
    val modifiers: List<String> = emptyList(),
    val returnType: String? = null,   // method / constructor
    val typeText: String? = null,     // field / property / variable / parameter
    val isReference: Boolean = false,
    val docText: String? = null,
)
@Serializable
data class SymbolAtResponse(
    val fileUrl: String, val offset: Int, val position: LineColumn,
    val symbol: SymbolInfo? = null, val warnings: List<String> = emptyList(),
)
@Serializable
data class OutlineNode(
    val name: String, val kind: String, val fqn: String? = null,
    val psiClass: String, val declarationRange: TextRangeInfo,
    val modifiers: List<String> = emptyList(),
    val returnType: String? = null, val typeText: String? = null,
    val children: List<OutlineNode> = emptyList(),
)
@Serializable
data class GetOutlineResponse(
    val fileUrl: String, val fileType: String, val language: String,
    val nodes: List<OutlineNode> = emptyList(),
    val nodeCount: Int = 0, val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
)
```

## IntelliJ APIs used

**`psi.symbol_at`**:
- `PsiManager` + `PsiFile.findElementAt` — leaf at offset.
- `InjectedLanguageManager.findInjectedElementAt` — injection-aware variant
  (same path as `PsiUsageSearcher.resolveTarget`).
- `PsiFile.findReferenceAt` + `PsiReference.resolve()` /
  `PsiPolyVariantReference.multiResolve(true)`.
- `PsiTreeUtil.getNonStrictParentOfType(leaf, PsiNamedElement::class.java)`.
- Modifiers: existing `core/PsiModifiers.kt` for Java. Kotlin
  `KtNamedDeclaration.fqName?.asString()` etc. accessed **reflectively**
  (Kotlin module is optional — same pattern as `ExecToolset`).
- Doc text: raw `PsiDocCommentOwner.docComment.text` (cheap, no HTML). Avoid
  `DocumentationProvider.generateDoc` — see open Q 2.

**`psi.get_outline`**:
- `LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)` —
  returns null when no extension contributes one.
- `TreeBasedStructureViewBuilder.createStructureViewModel(editor = null)` →
  `StructureViewModel.getRoot()` → recursive `TreeElement.getChildren()`.
- `TreeElement.value` is the underlying `PsiElement` — fed through
  `PsiKindClassifier` for kind / FQN / modifiers / ranges.
- `StructureViewModel.getGroupers()` intentionally **ignored** for v1 (open Q 1).
- Stable platform API:
  [Structure View docs](https://plugins.jetbrains.com/docs/intellij/structure-view.html).

## Threading & EDT model

Both wrap in `readActionBlocking { … }` — same pattern as
`psi_get_structure` / `psi_get_references`. No EDT bouncing —
`findElementAt`, `findReferenceAt`, `PsiTreeUtil`, `StructureViewModel`
walking are all read-action-safe.

`symbol_at` additionally wraps in
`DumbService.computeWithAlternativeResolveEnabled<T, RuntimeException>` because
it resolves references (same defence as `psi_get_references`). `get_outline`
skips this — structure-view walking doesn't touch the resolver.

Active-editor caret lookup (`fileUrl=null`) reuses the existing `resolveFile`
helper, which already bounces to EDT inside `caretOffsetOf`. No new cache —
both responses are cheap per call, and a TTL would risk staleness in the
edit-then-query loop.

## Timeout strategy

10 s read-action cap, inherited. Realistic worst case: `symbol_at` <50 ms;
`get_outline` <200 ms on a 10 000-line file with 500 methods, bounded by
`maxNodes`. Pathological structure-view contributors abort via the timeout
and surface as `McpExpectedError("Timed out…")` — same as existing `psi.*`.

## Edge cases

1. **Offset past EOF** — `resolveOffset` already clamps; `findElementAt` returns
   null; `symbol=null` + `"position past end of file"` warning.
2. **Whitespace / comment position** — leaf is `PsiWhiteSpace` / `PsiComment`;
   walk up via `getNonStrictParentOfType`. If nothing named found: `symbol=null`
   + warning.
3. **Injected fragments** — `findInjectedElementAt` first; injected ancestors
   win (SQL identifier reported, not host `KtLiteralStringTemplate`).
4. **Files without StructureViewBuilder** (binary / plain text / unknown) —
   `nodes=[]` + warning `"No StructureViewBuilder for fileType=$fileType"`.
5. **Anonymous classes / lambdas** — platform `TreeBasedStructureViewBuilder`
   excludes them from outline; we follow. `symbol_at` still resolves them
   normally with `name=null`.
6. **File closed in editor** — `VirtualFileManager.findFileByUrl` +
   `PsiManager.findFile` works without a `FileEditor`.
7. **Polyvariant reference** — pick first; warn
   `"$N other resolutions available — use psi.get_references for the full set"`.
8. **Local variable / parameter** — `fqn=null`, `containingDeclarationName` is
   the enclosing function.
9. **Light / synthetic PSI** — empty `name` falls through to `name=null,
   kind="unknown"` with warning.
10. **`maxNodes` hit mid-class in outline** — partial tree + `truncated=true` +
    warning.
11. **No focused project** — existing `requireProject()` handles it.

## Files to create/modify

| Path | Op | What |
|------|----|------|
| `tools/PsiToolset.kt` | Edit | Add `psi_symbol_at` + `psi_get_outline` + `@McpDescription`s |
| `core/PsiSymbolResolver.kt` | Create | `resolveAt(psiFile, offset, includeDoc, …) → SymbolInfo?` |
| `core/PsiOutlineCollector.kt` | Create | `collect(psiFile, includeFields, includeInherited, maxDepth, maxNodes) → GetOutlineResponse` via StructureViewBuilder |
| `core/PsiKindClassifier.kt` | Create | Pure classifier `PsiElement → kind`, shared (Java + Kotlin reflective) |
| `model/PsiInfo.kt` | Edit | Append SymbolInfo / SymbolAtResponse / LineColumn / OutlineNode / GetOutlineResponse |
| `src/test/kotlin/.../core/PsiKindClassifierTest.kt` | Create | Unit — mocked PSI mapping table |
| `src/test/kotlin/.../core/platform/PsiSymbolResolverPlatformTest.kt` | Create | Platform — Java + Kotlin fixtures |
| `src/test/kotlin/.../core/platform/PsiOutlineCollectorPlatformTest.kt` | Create | Platform — Java + Kotlin fixtures |

No new META-INF wiring: `psi.*` group already registered via `PsiToolset` in
`mcp-integration.xml`.

## Test plan

**Unit (`PsiKindClassifierTest.kt`)** — pure JVM:
- `PsiClass / interface / enum / annotation / record` → correct kind.
- `PsiMethod` → `method` / `constructor` via `isConstructor`.
- `PsiField` / `PsiParameter` / `PsiLocalVariable` → `field` / `parameter` /
  `variable`.
- Kotlin `KtClass / KtObjectDeclaration / KtNamedFunction / KtProperty /
  KtParameter` — reflective access only (Kotlin presence optional).
- Unknown element → `unknown`.

**Platform (`PsiSymbolResolverPlatformTest.kt`)** — `BasePlatformTestCase`,
fixtures under `src/test/testData/psi/`:
- `testSymbolOnDeclarationName` — caret on `class Foo` →
  `kind="class", fqn="pkg.Foo", isReference=false`.
- `testSymbolOnUsage` — caret on `foo()` call site → `isReference=true`,
  `declarationFileUrl` set.
- `testSymbolOnLocalVariable` — `fqn=null, kind="variable",
  containingDeclarationName="bar"`.
- `testSymbolPastEOF` / `testSymbolOnWhitespace` — `symbol=null` + warning.
- `testSymbolInsideInjection` — SQL-in-Kotlin-string; injected fragment wins.
- `testSymbolPolyvariant` — overloaded Java call; first resolution + count
  warning.
- `testKotlinKDocReturned` (gated on Kotlin module loaded) — `includeDoc=true`
  surfaces KDoc text.

**Platform (`PsiOutlineCollectorPlatformTest.kt`)**:
- `testJavaOutlineHasClassAndMethods` — class with field + method → class node
  with two children; FQN + modifiers asserted.
- `testKotlinOutlineHasTopLevelFunctions`.
- `testIncludeFieldsFalseSkipsFields`.
- `testIncludeInheritedTrueAddsSuperclassMethods`.
- `testNoStructureViewBuilderReturnsEmptyWithWarning` — `.bin` fixture.
- `testMaxNodesTruncation` — synthesize class with 600 methods; request
  `maxNodes=100` → `truncated=true` + 100 nodes.

## Estimated effort

| Step | Hours |
|------|-------|
| Models | 0.5 |
| `PsiKindClassifier` + unit tests | 1 |
| `PsiSymbolResolver` (incl. doc fetch + truncation) | 3 |
| `PsiOutlineCollector` (StructureViewModel walker) | 3 |
| `PsiToolset` methods + descriptions | 1 |
| Platform tests | 2.5 |
| Doc-gen verification + `runIde` smoke | 0.5 |
| **Total** | **~1.5 days** |

## Open questions / risks

1. **Outline groupers** — `StructureViewModel.getGroupers()` (Kotlin "Show
   Companion Objects Grouped", Java "Group Methods by Defining Type").
   **Decision: skip for v1**, matches the Structure tool window with all
   groupers disabled (the default). Revisit behind a `groupBy: String?` arg.
2. **`docText` formatting** — JetBrains' `get_symbol_info` returns a doc
   snippet; we follow precedent (`includeDoc=true` default) but use raw
   `PsiDocComment.text` (cheap, no HTML). Note in `@McpDescription` that
   markdown is not rendered.
3. **Outline for non-source files** — `.json`/`.yaml`/`.xml` have
   StructureViewBuilders (JSON keys, YAML doc tree). Passed through unchanged;
   non-symbol entries get `kind="unknown"`. Could add `sourceOnly: Boolean`
   later if noisy.
4. **`findReferenceAt` cost for heavy contributors** (PHP, Vue) — typically
   <100 ms, same risk surface as `psi_get_references` with `scope="at_offset"`,
   which already ships.
5. **Declaration in a library jar** — `declarationFileUrl` becomes
   `jar://…!/path/Foo.class`. Agents feed this into `code.get_class_source`
   (already handles jar URLs); cross-reference documented in description.

## References

- `tools/PsiToolset.kt#psi_get_references` — same position-resolution helpers
  (`resolveFile` + `resolveOffset`).
- `core/PsiUsageSearcher.kt#resolveTarget` — exact target-resolution pattern
  reused for `symbol_at`.
- `core/PsiModifiers.kt` — modifier vocabulary in place.
- IntelliJ source:
  - StructureViewBuilder:
    https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/ide/structureView/StructureViewBuilder.java
  - TreeBasedStructureViewBuilder:
    https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/ide/structureView/TreeBasedStructureViewBuilder.java
  - PsiNameIdentifierOwner:
    https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/PsiNameIdentifierOwner.java
  - Structure View docs:
    https://plugins.jetbrains.com/docs/intellij/structure-view.html
- JetBrains MCP equivalents:
  - `get_symbol_info` — partial overlap; theirs lacks `isReference` and uses a
    coarser kind taxonomy (class/method/field only); ours adds parameter,
    variable, typeAlias, enumConstant, label, companion, import.
  - `generate_psi_tree` — takes a raw code STRING (not a file); different use
    case. `psi.get_outline` is net-new.
