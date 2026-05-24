package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * One open editor tab (or "active" tab) reported by [com.github.xepozz.ide.introspector.tools.PsiToolset.psi_list_open_files].
 *
 * The pair (path, url) lets the agent either log a human-readable path or feed `url` back into
 * the rest of the `psi.*` tools — the URL form survives in-memory / scratch buffers / jar:// roots
 * that don't have a real filesystem path.
 *
 * `viewProviderLanguages` reports the multi-PSI nature of the file as IntelliJ sees it. A `.php`
 * file in IntelliJ has two language roots in its FileViewProvider (`PHP` + `HTML`) and any
 * `psi.get_structure` call will return both; this is the cheap hint that you should expect more
 * than one root, without parsing the file.
 */
@Serializable
data class OpenFileInfo(
    val path: String,
    val url: String,
    val fileType: String,
    val viewProviderLanguages: List<String>,
    val length: Int,
    /** Only set for the focused tab — caret offset in the editor's document. */
    val caretOffset: Int? = null,
)

@Serializable
data class OpenFilesResponse(
    val projectName: String,
    /** The currently focused tab in the project, or null if no editor is selected. */
    val activeFile: OpenFileInfo? = null,
    val openFiles: List<OpenFileInfo> = emptyList(),
)

/** Absolute text range in the host (top-level) file, plus 1-based line/column for human readability. */
@Serializable
data class TextRangeInfo(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

/**
 * One PSI element node in the flat pre-order DFS of [PsiFileTree.nodes]. The `id` / `parentId`
 * pair encodes the tree without nesting:
 *   - root nodes have parentId == null and id "<rootIdx>"
 *   - child of "0" at position 3 has id "0.3", etc.
 *
 * `hasReferences` is the cheap signal — `element.references.isNotEmpty()` doesn't resolve, so it's
 * safe to compute on every node, and lets the agent skip the heavy `psi.get_references` call on
 * leaves that obviously can't have one (whitespace, comments).
 */
@Serializable
data class PsiNode(
    val id: String,
    val parentId: String? = null,
    val psiClass: String,
    /** ASTNode.elementType.toString() — token type for leaves ("IDENTIFIER", "WHITE_SPACE"), grammar rule for composites. */
    val elementType: String,
    val textRange: TextRangeInfo,
    val text: String? = null,
    val hasReferences: Boolean = false,
    /** True when this element can host injected languages (PsiLanguageInjectionHost). */
    val isInjectionHost: Boolean = false,
    val childCount: Int = 0,
)

/**
 * One language root inside a file's FileViewProvider. A `.php` file produces two of these
 * (PHP + HTML); a `.vue` file typically produces four (Vue + JavaScript + CSS + HTML). The
 * `psiFileClass` lets the agent distinguish them programmatically.
 */
@Serializable
data class PsiFileTree(
    val language: String,
    val psiFileClass: String,
    val nodes: List<PsiNode>,
    /** Set when the per-tree node limit was hit — the tree is a prefix of the real one. */
    val truncated: Boolean = false,
)

/**
 * Injected language anchored on a host PsiElement inside one of the [GetPsiStructureResponse.psiFiles].
 *
 * Examples: SQL injected into a Kotlin string literal, regex injected into a JS string, JavaScript
 * in an HTML `<script>` tag. The injection's offsets in `nodes[].textRange` are relative to the
 * INJECTED document; `hostRange` maps the injection back onto the host file's offsets so the
 * agent can correlate the two.
 */
@Serializable
data class PsiInjectionTree(
    val hostNodeId: String?,                // matches PsiNode.id of the host element, if found
    val hostRange: TextRangeInfo,           // where the injection sits in the host file
    val tree: PsiFileTree,
)

@Serializable
data class GetPsiStructureResponse(
    val fileUrl: String,
    val fileType: String,
    val length: Int,
    val psiFiles: List<PsiFileTree>,
    val injections: List<PsiInjectionTree> = emptyList(),
    val truncated: Boolean = false,
    val nodeCount: Int = 0,
    val warnings: List<String> = emptyList(),
)

/**
 * One resolution target for a single PsiReference. References can resolve to multiple targets
 * (PsiPolyVariantReference, e.g. an overloaded method call, an ambiguous identifier) — see
 * [ResolvedReference.targets] for the list.
 *
 * `sameFile` is set when the target lives in the same FileViewProvider as the source — typical
 * for local variables / private methods. When sameFile=false, `targetFileUrl` points at the
 * declaring file (could be a library jar, another project module, etc.).
 */
@Serializable
data class ResolveTarget(
    val resolved: Boolean,
    val targetPsiClass: String? = null,
    val targetText: String? = null,
    val targetRange: TextRangeInfo? = null,
    val targetFileUrl: String? = null,
    val sameFile: Boolean = false,
    /** Name of the target if it implements PsiNamedElement — e.g. variable name, method name. */
    val declarationName: String? = null,
)

@Serializable
data class ResolvedReference(
    /** Stable id of the source PsiElement, matching [PsiNode.id] from a sibling psi.get_structure call. */
    val sourceNodeId: String? = null,
    val sourcePsiClass: String,
    val sourceText: String,
    /** Absolute range in the host file of the *reference* (element textRange + ref rangeInElement). */
    val sourceRange: TextRangeInfo,
    val referenceClass: String,
    /** Soft references (e.g. completion-only) may not light up Ctrl-click but still resolve. */
    val isSoft: Boolean = false,
    val targets: List<ResolveTarget> = emptyList(),
)

@Serializable
data class GetReferencesResponse(
    val fileUrl: String,
    val scope: String,
    val references: List<ResolvedReference>,
    val total: Int,
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
)

/**
 * The declaration `psi.find_usages` resolved to. Either picked up from a reference under the
 * caret (Ctrl-click semantics) or — when the caret sits on a declaration site — the nearest
 * containing [com.intellij.psi.PsiNamedElement]. The agent can use this to confirm that the
 * platform agreed with its mental model before reading the usage list.
 */
@Serializable
data class TargetInfo(
    val psiClass: String,
    val declarationName: String? = null,
    val fileUrl: String,
    val range: TextRangeInfo,
    /** First-line preview of the declaration, truncated. */
    val text: String,
)

/**
 * A single usage site found by `psi.find_usages`.
 *
 * `kind` distinguishes:
 *   "reference"      — classic call/read/write site discovered via ReferencesSearch
 *   "implementation" — an overriding method or implementing/extending class discovered via
 *                      DefinitionsScopedSearch (same source as Ctrl+Alt+B "Goto Implementation")
 *
 * `lineSnippet` is the trimmed-and-truncated line of code the usage sits on — what IntelliJ's
 * Find Usages tool window shows beside each hit. Far more useful to an agent than a bare
 * text range, since it provides surrounding context without a follow-up file read.
 *
 * `containingDeclaration` is the enclosing method/class/field name (the "in foo() of class Bar"
 * grouping in the Find Usages tool window). Lets the agent answer "where is this used?" in
 * human-meaningful terms.
 */
@Serializable
data class UsageInfo(
    val kind: String,
    val fileUrl: String,
    val fileType: String,
    val range: TextRangeInfo,
    val text: String,
    val lineSnippet: String,
    val referenceClass: String? = null,
    val isSoft: Boolean = false,
    val containingDeclaration: String? = null,
)

/** Group of usages in one file — populated only when groupByFile=true. */
@Serializable
data class FileUsages(
    val fileUrl: String,
    val fileType: String,
    val usages: List<UsageInfo>,
)

@Serializable
data class FindUsagesResponse(
    val target: TargetInfo,
    val scope: String,
    val usages: List<UsageInfo> = emptyList(),
    val byFile: List<FileUsages> = emptyList(),
    val total: Int,
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
)

// ============================================================================
// psi.symbol_at + psi.get_outline
// ============================================================================

/** 1-based line / column pair — surfaces the position [com.github.xepozz.ide.introspector.tools.PsiToolset.psi_symbol_at] resolved to, regardless of how the caller asked for it. */
@Serializable
data class LineColumn(val line: Int, val column: Int)

/**
 * Compact description of one PSI symbol returned by `psi.symbol_at`.
 *
 * Kind taxonomy (mirrors what IntelliJ's Structure tool window distinguishes):
 *   class | interface | enum | annotation | record | object | companion |
 *   method | constructor | field | property | parameter | variable |
 *   typeAlias | enumConstant | import | label | unknown
 *
 * Reference vs. declaration disambiguation: when the caret sits on a usage, `isReference=true`
 * and the rest of the fields (name/kind/fqn/declarationRange/declarationFileUrl/…) describe
 * the resolved DECLARATION. When the caret is on the declaration itself, `isReference=false`
 * and those fields describe the declaration directly. Either way, the caller gets one
 * declaration-level description per call — no follow-up resolve.
 *
 * `fqn` is populated for top-level / member declarations. Locals (variable, parameter) have
 * `fqn=null`; their context comes from `containingDeclarationName`.
 */
@Serializable
data class SymbolInfo(
    val name: String?,
    val kind: String,
    val fqn: String? = null,
    val psiClass: String,
    val declarationRange: TextRangeInfo,
    val declarationFileUrl: String,
    val containingDeclarationName: String? = null,
    val modifiers: List<String> = emptyList(),
    /** Only set for method / constructor. */
    val returnType: String? = null,
    /** Only set for field / property / variable / parameter. */
    val typeText: String? = null,
    val isReference: Boolean = false,
    val docText: String? = null,
)

@Serializable
data class SymbolAtResponse(
    val fileUrl: String,
    val offset: Int,
    val position: LineColumn,
    val symbol: SymbolInfo? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * One node in the outline tree returned by `psi.get_outline`.
 *
 * Each node is a declaration the Structure tool window would show — bodies / statements /
 * expressions are deliberately omitted. The recursive [children] list is the same outline
 * shape, capped collectively by the outer response's `maxNodes`. Kind values use the same
 * vocabulary as [SymbolInfo.kind].
 *
 * `fqn` is null for local-scope / anonymous nodes; `returnType` is populated only for
 * methods / constructors; `typeText` only for fields / properties.
 */
@Serializable
data class OutlineNode(
    val name: String,
    val kind: String,
    val fqn: String? = null,
    val psiClass: String,
    val declarationRange: TextRangeInfo,
    val modifiers: List<String> = emptyList(),
    val returnType: String? = null,
    val typeText: String? = null,
    val children: List<OutlineNode> = emptyList(),
)

@Serializable
data class GetOutlineResponse(
    val fileUrl: String,
    val fileType: String,
    val language: String,
    val nodes: List<OutlineNode> = emptyList(),
    val nodeCount: Int = 0,
// =====================================================================================
// psi.type_hierarchy
// =====================================================================================

/**
 * Compact reference to a class participating in a type hierarchy (the target itself, a
 * parent, or a child). For library classes `fileUrl` is a `jar://…` URL and
 * `declarationRange` is the range of the class declaration inside the (possibly
 * decompiled) source. Anonymous / local classes are recognised at a position but
 * never appear as a subtype node (no usable FQN).
 */
@Serializable
data class HierarchyClassRef(
    val fqn: String? = null,
    val psiClass: String,
    val fileUrl: String? = null,
    val declarationRange: TextRangeInfo? = null,
    val isInterface: Boolean = false,
    val isAbstract: Boolean = false,
    val isFinal: Boolean = false,
    val isSealed: Boolean = false,
    val modifiers: List<String> = emptyList(),
)

/**
 * One node in a type-hierarchy tree (rooted at the target). `children` walks the
 * direction-specific neighbourhood — for supertype trees the children are the parents
 * (extends / implements), for subtype trees the children are the direct extenders /
 * implementors. `childrenTruncated` is set on the leaf where a depth or node cap cut
 * the recursion.
 */
@Serializable
data class HierarchyNode(
    val node: HierarchyClassRef,
    val children: List<HierarchyNode> = emptyList(),
    val childrenTruncated: Boolean = false,
)

@Serializable
data class TypeHierarchyResponse(
    val target: HierarchyClassRef,
    val supertypes: HierarchyNode? = null,
    val subtypes: HierarchyNode? = null,
    val direction: String,
    val scope: String,
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
)

// =====================================================================================
// psi.goto_implementation
// =====================================================================================

/**
 * The symbol `psi.goto_implementation` resolved to under the caret. `kind` is either
 * "class" (caret on an interface / abstract class / class ref) or "method" (caret on
 * an abstract / interface method or a method call). For library targets `fileUrl` is a
 * `jar://…` URL.
 */
@Serializable
data class ImplementationTarget(
    val name: String? = null,
    val psiClass: String,
    /** "class" — implementors / extenders ; "method" — overriders. */
    val kind: String,
    val fileUrl: String? = null,
    val declarationRange: TextRangeInfo? = null,
    val isAbstract: Boolean = false,
    val isInterface: Boolean = false,
)

/**
 * One concrete implementation / override found for the target.
 *
 *   - `signature` is null when `target.kind == "class"`; for methods it is the
 *     overrider's erasure as it appears in the overrider's source ("R name(P p)") —
 *     no cross-boundary generic unification.
 *   - `isAbstract` flags intermediate overrides that are themselves abstract.
 *   - `isOverride` is true for method results (mirrors `@Override`-equivalent semantics),
 *     false for class results.
 */
@Serializable
data class ImplementationInfo(
    val fileUrl: String,
    val range: TextRangeInfo,
    val lineSnippet: String,
    val declaringClassFqn: String? = null,
    val signature: String? = null,
    val isAbstract: Boolean = false,
    val isOverride: Boolean = false,
)

@Serializable
data class GotoImplementationResponse(
    val target: ImplementationTarget,
    val implementations: List<ImplementationInfo> = emptyList(),
    val scope: String,
    val total: Int,
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
)
