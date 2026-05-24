package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.PsiOutlineCollector
import com.github.xepozz.ide.introspector.core.PsiHierarchyResolver
import com.github.xepozz.ide.introspector.core.PsiReferenceCollector
import com.github.xepozz.ide.introspector.core.PsiStructureWalker
import com.github.xepozz.ide.introspector.core.PsiSymbolResolver
import com.github.xepozz.ide.introspector.core.PsiUsageSearcher
import com.github.xepozz.ide.introspector.model.FindUsagesResponse
import com.github.xepozz.ide.introspector.model.GetOutlineResponse
import com.github.xepozz.ide.introspector.model.GetPsiStructureResponse
import com.github.xepozz.ide.introspector.model.GetReferencesResponse
import com.github.xepozz.ide.introspector.model.GotoImplementationResponse
import com.github.xepozz.ide.introspector.model.OpenFileInfo
import com.github.xepozz.ide.introspector.model.OpenFilesResponse
import com.github.xepozz.ide.introspector.util.PositionResolver
import com.github.xepozz.ide.introspector.model.SymbolAtResponse
import com.github.xepozz.ide.introspector.model.TypeHierarchyResponse
import com.github.xepozz.ide.introspector.util.onEdtBlocking
import com.github.xepozz.ide.introspector.util.readActionBlocking
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilBase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject

/**
 * `psi.*` — open-file inspection: enumerate open editor tabs, dump the PSI tree (multi-language
 * via FileViewProvider, including injected languages), and list/resolve references in the file.
 *
 * The toolset is the agent equivalent of IntelliJ's bundled PSI Viewer (Tools > View PSI Structure
 * of Current File) — see `com.intellij.dev.psiViewer.PsiViewerDialog` in the IDE's `dev` plugin.
 * Tree-walking and reference-resolution logic is lifted from there so the agent observes the same
 * structure a developer would see in the viewer.
 *
 * Threading: all work runs under a platform read action via `readActionBlocking` (10 s cap).
 * `FileEditorManager` is only consulted under [psi_list_open_files], which bounces onto the EDT.
 */
class PsiToolset : McpToolset {

    @McpTool(name = "psi.list_open_files")
    @McpDescription(
        """
        |Lists the editor tabs currently open in the focused project, identifying the focused tab
        |separately. Cheap probe — no PSI parsing.
        |
        |Use this when:
        |  - You want to know which file the user is looking at right now ("the open file").
        |  - You need to feed `fileUrl` to psi.get_structure / psi.get_references and want to
        |    confirm what's available rather than guessing.
        |  - The user has multiple files open and you need to disambiguate.
        |
        |Do NOT use this when:
        |  - You already have a known absolute path / URL — the structure/reference tools accept
        |    either form directly.
        |  - You need file content (this returns only metadata). Use code.* for class sources,
        |    psi.get_structure for the PSI of a tab.
        |
        |Returns: { projectName, activeFile?, openFiles[] }. Each entry has path, url (VFS form
        |that works for non-filesystem files like in-memory scratches), fileType, length,
        |viewProviderLanguages, and `caretOffset` for the focused tab.
        |
        |The `viewProviderLanguages` field reports the multi-PSI nature of the file as IntelliJ
        |sees it — a .php file shows ["PHP", "HTML"], a .vue file shows ["Vue", "JavaScript",
        |"CSS", "HTML"]. psi.get_structure will return one tree per language.
        |
        |Examples:
        |  (no args)   — list open tabs, identify the focused tab, return urls for follow-ups
        """
    )
    suspend fun psi_list_open_files(): OpenFilesResponse {
        val project = requireProject()
        // FileEditorManager.selectedFiles is EDT-only (queries the focus subsystem).
        val (selectedVf, allVfs) = onEdtBlocking {
            val mgr = FileEditorManager.getInstance(project)
            mgr.selectedFiles.firstOrNull() to mgr.openFiles.toList()
        }
        return readActionBlocking {
            val psiMgr = PsiManager.getInstance(project)
            val docMgr = PsiDocumentManager.getInstance(project)
            val active = selectedVf?.let { describeOpenFile(it, psiMgr, docMgr, isActive = true) }
            val all = allVfs.mapNotNull { vf ->
                describeOpenFile(vf, psiMgr, docMgr, isActive = vf == selectedVf)
            }
            OpenFilesResponse(
                projectName = project.name,
                activeFile = active,
                openFiles = all,
            )
        }
    }

    @McpTool(name = "psi.get_structure")
    @McpDescription(
        """
        |Returns the full PSI tree of an open file — every language root in the file's view
        |provider, plus any injected language fragments (SQL in a Kotlin string, JavaScript in
        |an HTML <script> tag, etc.). Walks the AST so leaf tokens (identifiers, punctuation)
        |appear, mirroring IntelliJ's bundled PSI Viewer.
        |
        |Use this when:
        |  - You need to understand the syntactic structure of a file the user has open.
        |  - You want to locate which PSI element corresponds to a specific text range.
        |  - You're preparing to call psi.get_references — the node ids here match the
        |    sourceNodeId in references output.
        |
        |Do NOT use this when:
        |  - You only want resolved references for the cursor position — call psi.get_references
        |    with scope="at_offset" directly.
        |  - You just need the open file's path/length — psi.list_open_files is cheaper.
        |
        |Multi-PSI: a .php file returns two roots (PHP + HTML); a .vue file returns four. Each
        |root's `language` field identifies the language. Don't assume `psiFiles[0]` is "the"
        |file — iterate.
        |
        |Injections: when includeInjections=true (default), injected language fragments are
        |returned in a separate `injections[]` array. Each has `hostNodeId` (the source-file PSI
        |element that hosts the injection — typically a string literal) and `hostRange` (where
        |the injection lives in the host file's offsets). Node textRanges INSIDE an injection are
        |offsets in the injected document, not the host — use hostRange to correlate.
        |
        |Returns: { fileUrl, fileType, length, psiFiles[], injections[], truncated, nodeCount }.
        |Each PsiNode carries a stable id ("0.3.1"), psiClass, elementType, absolute textRange
        |(with line+column), childCount, hasReferences (cheap signal for whether
        |psi.get_references will find anything on this node), and isInjectionHost.
        |
        |Cost: O(nodes). Hard-capped at maxNodes (default 5000). Defaults skip whitespace and
        |inline text — pass includeText=true to also embed the (truncated) source text per node.
        |
        |Examples:
        |  fileUrl=null                       — uses the active editor tab
        |  fileUrl="file:///…/foo.php"        — explicit file
        |  includeWhitespace=true             — also show WHITE_SPACE tokens (verbose)
        |  includeText=true, truncateNodeTextAt=200 — show 200-char snippets
        """
    )
    suspend fun psi_get_structure(
        @McpDescription("VFS URL of the file (from psi.list_open_files.url). null → use the active editor tab.")
        fileUrl: String? = null,
        @McpDescription("Max tree depth from each language root. Default 20.")
        maxDepth: Int = 20,
        @McpDescription("Hard cap on the total node count across all psiFiles + injections. Default 5000.")
        maxNodes: Int = 5_000,
        @McpDescription("Include WHITE_SPACE token nodes. Default false. Big files double in size with this on.")
        includeWhitespace: Boolean = false,
        @McpDescription("Embed the (truncated) source text of every node. Default false.")
        includeText: Boolean = false,
        @McpDescription("Max chars per node text when includeText=true. Longer text is suffixed with '…'.")
        truncateNodeTextAt: Int = 80,
        @McpDescription("Enumerate injected languages (e.g. SQL in a Kotlin string). Default true.")
        includeInjections: Boolean = true,
    ): GetPsiStructureResponse {
        require(maxDepth in 1..200) { "maxDepth must be in 1..200" }
        require(maxNodes in 1..50_000) { "maxNodes must be in 1..50000" }
        require(truncateNodeTextAt in 0..4096) { "truncateNodeTextAt must be in 0..4096" }

        val project = requireProject()
        return readActionBlocking {
            val (psiFile, vf, document) = resolveFile(project, fileUrl)
            DumbService.getInstance(project).computeWithAlternativeResolveEnabled<GetPsiStructureResponse, RuntimeException> {
                val result = PsiStructureWalker.walk(
                    project = project,
                    rootFile = psiFile,
                    hostDocument = document,
                    maxDepth = maxDepth,
                    maxNodes = maxNodes,
                    includeWhitespace = includeWhitespace,
                    includeText = includeText,
                    truncateNodeTextAt = truncateNodeTextAt,
                    includeInjections = includeInjections,
                )
                GetPsiStructureResponse(
                    fileUrl = vf.url,
                    fileType = psiFile.fileType.name,
                    length = document?.textLength ?: psiFile.textLength,
                    psiFiles = result.files,
                    injections = result.injections,
                    truncated = result.truncated,
                    nodeCount = result.nodeCount,
                )
            }
        }
    }

    @McpTool(name = "psi.get_references")
    @McpDescription(
        """
        |Returns PSI references in a file, with their resolved declarations. A reference is a
        |link from one element (a usage) to another (the declaration) — the classic case is
        |  ${'$'}var = 1;
        |  echo ${'$'}var;   // <- the second variable has a reference to the first
        |Resolution uses PsiReferenceService (the same path IntelliJ Ctrl-click does), so
        |contributed references from psi.referenceContributor extension points are included.
        |
        |Use this when:
        |  - You need to know what a symbol at the cursor points to (scope="at_offset").
        |  - You want a complete usage→declaration map of a file (scope="file"). Combine with
        |    psi.get_structure — `sourceNodeId` here matches PsiNode.id there.
        |
        |Do NOT use this when:
        |  - You want the reverse direction ("who uses this declaration?") — this is Find Usages,
        |    not a current `psi.*` capability. Use ReferencesSearch via exec.* if needed.
        |  - You need the full PSI tree — psi.get_structure is the right tool; this only returns
        |    elements that actually carry references.
        |
        |Returns: { fileUrl, scope, references[], total, truncated }. Each ResolvedReference has
        |sourceText, sourceRange (absolute in the host file), referenceClass, isSoft, and a
        |targets[] list. PsiPolyVariantReference (overloaded method calls, ambiguous identifiers)
        |produces multiple targets when includeMultiResolve=true.
        |
        |Each ResolveTarget has:
        |  resolved          — false if the reference is dangling (target removed, never existed)
        |  targetPsiClass    — e.g. "AssignmentExpression" for the declaration site
        |  targetRange       — absolute range in the target file
        |  targetFileUrl     — null if the target lives in the same view provider (sameFile=true),
        |                      otherwise the VFS URL (could point into a library jar)
        |  declarationName   — for PsiNamedElement targets, the name (variable name, method name)
        |
        |Position: pass `offset` OR `line`+`column` (1-based) to use scope="at_offset". With
        |scope="file" the positional args are ignored.
        |
        |Examples:
        |  fileUrl=null, scope="at_offset", line=12, column=5 — references at row 12, col 5 in the active file
        |  fileUrl="file:///…/foo.php", scope="file"          — every reference in foo.php
        """
    )
    suspend fun psi_get_references(
        @McpDescription("VFS URL of the file. null → active editor tab.")
        fileUrl: String? = null,
        @McpDescription("Document offset of the position to inspect. Used when scope=\"at_offset\".")
        offset: Int? = null,
        @McpDescription("1-based line number. Alternative to `offset`. Used when scope=\"at_offset\".")
        line: Int? = null,
        @McpDescription("1-based column number. Alternative to `offset`. Used when scope=\"at_offset\".")
        column: Int? = null,
        @McpDescription("\"file\" (default) walks all references in the file; \"at_offset\" inspects one PSI element under the cursor / supplied position.")
        scope: String = "file",
        @McpDescription("Expand PsiPolyVariantReference (overloaded method calls) into multiple targets. Default true.")
        includeMultiResolve: Boolean = true,
        @McpDescription("Hard cap on returned references. Default 1000.")
        maxReferences: Int = 1_000,
        @McpDescription("Max chars per sourceText / targetText. Longer is suffixed with '…'. Default 80.")
        truncateTextAt: Int = 80,
    ): GetReferencesResponse {
        require(scope == "file" || scope == "at_offset") {
            "scope must be 'file' or 'at_offset', got '$scope'"
        }
        require(maxReferences in 1..50_000) { "maxReferences must be in 1..50000" }
        require(truncateTextAt in 0..4096) { "truncateTextAt must be in 0..4096" }

        val project = requireProject()
        return readActionBlocking {
            val (psiFile, vf, document) = resolveFile(project, fileUrl)

            // We need the element-id map produced by the structure walker so references can
            // cite stable ids. Walk to gather only — no text, no whitespace, no injections (the
            // collector explores all view-provider files itself).
            val walkResult = PsiStructureWalker.walk(
                project = project,
                rootFile = psiFile,
                hostDocument = document,
                maxDepth = 200,
                maxNodes = 50_000,
                includeWhitespace = false,
                includeText = false,
                truncateNodeTextAt = 0,
                includeInjections = false,
            )

            DumbService.getInstance(project).computeWithAlternativeResolveEnabled<GetReferencesResponse, RuntimeException> {
                when (scope) {
                    "file" -> {
                        val (refs, truncated) = PsiReferenceCollector.collectInFile(
                            rootFile = psiFile,
                            hostDocument = document,
                            maxReferences = maxReferences,
                            truncateTextAt = truncateTextAt,
                            includeMultiResolve = includeMultiResolve,
                            elementIds = walkResult.elementIds,
                        )
                        GetReferencesResponse(
                            fileUrl = vf.url,
                            scope = scope,
                            references = refs,
                            total = refs.size,
                            truncated = truncated,
                        )
                    }
                    else -> { // at_offset
                        val resolvedOffset = resolveOffset(document, offset, line, column)
                        val element = PsiUtilBase.getElementAtOffset(psiFile, resolvedOffset)
                        val refs = PsiReferenceCollector.collectAtElement(
                            element = element,
                            hostDocument = document,
                            truncateTextAt = truncateTextAt,
                            includeMultiResolve = includeMultiResolve,
                            elementIds = walkResult.elementIds,
                        )
                        GetReferencesResponse(
                            fileUrl = vf.url,
                            scope = scope,
                            references = refs,
                            total = refs.size,
                            truncated = false,
                        )
                    }
                }
            }
        }
    }

    @McpTool(name = "psi.find_usages")
    @McpDescription(
        """
        |Finds every usage of the declaration at a given position — the inverse of
        |psi.get_references. Given a caret position, identifies what declaration the user means
        |(follow a reference if on a usage; otherwise the nearest named declaration) and returns
        |all sites that refer to it. Same direction as IntelliJ's Find Usages action.
        |
        |Use this when:
        |  - You want to know "where is this used?" — the agent has identified a declaration
        |    (a class, method, variable, function) and needs to see all call sites / read sites.
        |  - You're assessing impact of a rename / removal — every site in `usages[]` would need
        |    updating.
        |  - You need overrides / implementations — `includeImplementations=true` (default) folds
        |    them in (same source as Ctrl+Alt+B "Goto Implementation").
        |
        |Do NOT use this when:
        |  - You only want what a particular use-site points TO — that's psi.get_references with
        |    scope="at_offset".
        |  - You need the full PSI tree — psi.get_structure is the right tool.
        |
        |Position: pass `offset` OR `line`+`column` (1-based). The caret can be either on a
        |usage (we follow the reference) or on the declaration itself.
        |
        |Scope (default "project"):
        |  - "file"    — only this file. Cheap and lossless for file-local symbols.
        |  - "project" — all project sources. Standard Find Usages scope; default.
        |  - "all"     — includes library sources. Beware: searching for a JDK symbol in `all`
        |                scope can saturate the 10 s read-action timeout. Use sparingly.
        |
        |Local-variable scoping: if the resolved target is a local variable or parameter, the
        |scope is auto-narrowed to LocalSearchScope(containing-file) regardless of the requested
        |scope — a project-wide search for a local `i` is meaningless.
        |
        |Returns: { target, scope, usages[] (or byFile[] when groupByFile=true), total,
        |truncated }. `target` confirms which declaration we landed on (psiClass +
        |declarationName + range). Each UsageInfo has fileUrl, range (absolute, with line/col),
        |text (the reference's source text), `lineSnippet` (the entire trimmed line of source
        |around the hit — like IntelliJ's Find Usages tool window), `containingDeclaration`
        |(the enclosing method/class for context), and `kind`:
        |  - "reference"      — classic call/read/write site
        |  - "implementation" — an overriding method / implementing/extending class (when
        |                        includeImplementations=true)
        |
        |Examples:
        |  fileUrl=null, line=12, column=8                 — Find Usages on the symbol at row 12, col 8 in the active file
        |  fileUrl="file:///…/Service.kt", offset=420      — explicit file + byte offset
        |  scope="file"                                    — only same-file references / overrides
        |  groupByFile=true                                — usages bucketed per file
        |  includeImplementations=false                    — only references, no overrides/subclasses
        """
    )
    suspend fun psi_find_usages(
        @McpDescription("VFS URL of the file. null → active editor tab.")
        fileUrl: String? = null,
        @McpDescription("Document offset of the position to inspect. Alternative to line+column.")
        offset: Int? = null,
        @McpDescription("1-based line number. Alternative to `offset`.")
        line: Int? = null,
        @McpDescription("1-based column number. Alternative to `offset`.")
        column: Int? = null,
        @McpDescription("\"project\" (default) / \"file\" / \"all\" (includes library sources — slow).")
        scope: String = "project",
        @McpDescription("Also include overriding methods / implementing-or-extending classes via DefinitionsScopedSearch. Default true.")
        includeImplementations: Boolean = true,
        @McpDescription("Hard cap on returned usages. Default 500.")
        maxUsages: Int = 500,
        @McpDescription("Max chars per `text` / `lineSnippet` / target preview. Longer is suffixed with '…'. Default 120.")
        truncateTextAt: Int = 120,
        @McpDescription("Group hits by fileUrl into byFile[] instead of a flat usages[] list. Default false.")
        groupByFile: Boolean = false,
    ): FindUsagesResponse {
        require(scope == "project" || scope == "file" || scope == "all") {
            "scope must be one of: project, file, all — got '$scope'"
        }
        require(maxUsages in 1..50_000) { "maxUsages must be in 1..50000" }
        require(truncateTextAt in 0..4096) { "truncateTextAt must be in 0..4096" }

        val project = requireProject()
        return readActionBlocking {
            val (psiFile, _, document) = resolveFile(project, fileUrl)
            val pos = resolveOffset(document, offset, line, column)
            DumbService.getInstance(project).computeWithAlternativeResolveEnabled<FindUsagesResponse, RuntimeException> {
                try {
                    PsiUsageSearcher.findUsages(
                        project = project,
                        psiFile = psiFile,
                        offset = pos,
                        scopeKind = scope,
                        includeImplementations = includeImplementations,
                        maxUsages = maxUsages,
                        truncateTextAt = truncateTextAt,
                        groupByFile = groupByFile,
                    )
                } catch (e: PsiUsageSearcher.NoTargetException) {
                    throw McpExpectedError(e.message ?: "No declaration at offset", JsonObject(emptyMap()))
                }
            }
        }
    }

    @McpTool(name = "psi.symbol_at")
    @McpDescription(
        """
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
        |                          or no doc present. Raw text — markdown is not rendered.
        |
        |When the position resolves to nothing (whitespace, comment, EOF, binary file):
        |symbol = null with a warning.
        |
        |Examples:
        |  fileUrl=null, line=12, column=8     — symbol under caret at row 12 col 8 of active tab
        |  fileUrl="file:///…/Foo.kt", offset=420
        |  includeDoc=false                    — skip KDoc lookup for speed
        """
    )
    suspend fun psi_symbol_at(
        @McpDescription("VFS URL of the file. null → active editor tab.")
        fileUrl: String? = null,
        @McpDescription("Document offset. Alternative to line+column.")
        offset: Int? = null,
        @McpDescription("1-based line. Alternative to `offset`.")
        line: Int? = null,
        @McpDescription("1-based column. Alternative to `offset`.")
        column: Int? = null,
        @McpDescription("Include KDoc / JavaDoc text. Default true.")
        includeDoc: Boolean = true,
        @McpDescription("Max chars of doc text. Longer is suffixed with '…'. Default 400.")
        truncateDocAt: Int = 400,
    ): SymbolAtResponse {
        require(truncateDocAt in 0..4096) { "truncateDocAt must be in 0..4096" }

        val project = requireProject()
        return readActionBlocking {
            val (psiFile, vf, document) = resolveFile(project, fileUrl)
            val resolvedOffset = resolveOffset(document, offset, line, column)
            DumbService.getInstance(project).computeWithAlternativeResolveEnabled<SymbolAtResponse, RuntimeException> {
                val resp = PsiSymbolResolver.resolveAt(
                    psiFile = psiFile,
                    hostDocument = document,
                    offset = resolvedOffset,
                    includeDoc = includeDoc,
                    truncateDocAt = truncateDocAt,
                )
                // The resolver uses psiFile.virtualFile.url for fileUrl which equals vf.url —
                // but tests should also work when the file was loaded via PSI without a backing
                // VirtualFile (rare). Ensure we always return a fileUrl from the resolved request.
                if (resp.fileUrl.isEmpty()) resp.copy(fileUrl = vf.url) else resp
    @McpTool(name = "psi.type_hierarchy")
    @McpDescription(
        """
        |Returns the type hierarchy of a class — supertypes (parents) and/or subtypes
        |(implementors / extenders) — as a tree rooted at the target. Mirrors IntelliJ's
        |Hierarchy tool window (Ctrl+H, "Type Hierarchy").
        |
        |Use this when:
        |  - The agent needs what a class extends / implements ("up").
        |  - The agent needs who extends or implements a class ("down").
        |  - A multi-level tree is more useful than a flat list.
        |  - Sealed-type exhaustiveness check — direct subtypes included, `isSealed` flagged.
        |
        |Do NOT use this when:
        |  - You only need concrete impls of an interface / abstract member —
        |    psi.goto_implementation is more focused and returns method signatures.
        |  - You want references / call sites — that is psi.find_usages.
        |
        |Target: pass `target` (FQN, takes precedence) OR a position (fileUrl + offset
        |OR line+column) on a class decl / reference. Anonymous + local classes are
        |recognised at a position but never appear as subtype nodes (no FQN).
        |
        |Scope (default "project"): "file" (rare), "project" (default), "all" (includes
        |library sources — for hot types like java.util.List or java.lang.Object the
        |subtype walk can saturate the 10s read-action timeout; a warning is appended).
        |
        |Caps: maxDepth (5) and maxNodes (200) bound the walk. On a cap, `truncated`
        |is set and the cut branch's leaf carries `childrenTruncated=true`.
        |
        |Returns: { target: HierarchyClassRef, supertypes: HierarchyNode?, subtypes:
        |HierarchyNode?, direction, scope, truncated, warnings[] }. Each HierarchyNode
        |has `node` + `children[]` (parents for supertypes, child classes for subtypes).
        |java.lang.Object is included as supertype root when walking "up" but its
        |subtype walk is always rejected (would be the world).
        |
        |Examples:
        |  target="com.intellij.openapi.editor.Editor"         — both directions, project
        |  target="java.util.List", direction="up"             — super-interfaces only
        |  fileUrl=null, line=42, column=14, direction="down"  — subtypes under caret
        |  target="com.acme.Sealed", direction="down"          — exhaustive sealed list
        """
    )
    suspend fun psi_type_hierarchy(
        @McpDescription("FQN of the target class (e.g. \"com.intellij.openapi.editor.Editor\"). Takes precedence over the positional args.")
        target: String? = null,
        @McpDescription("VFS URL of the file holding the target. null → active editor tab. Only used when `target` is null.")
        fileUrl: String? = null,
        @McpDescription("Document offset of the caret. Used when `target` is null. Alternative to line+column.")
        offset: Int? = null,
        @McpDescription("1-based line number. Used when `target` is null. Alternative to `offset`.")
        line: Int? = null,
        @McpDescription("1-based column number. Used when `target` is null. Pair with `line`.")
        column: Int? = null,
        @McpDescription("\"up\" (supertypes), \"down\" (subtypes), or \"both\" (default).")
        direction: String = "both",
        @McpDescription("\"project\" (default) / \"all\" (includes library sources, may time out on hot types) / \"file\" (rare).")
        scope: String = "project",
        @McpDescription("Max tree depth from the target (1..20). Default 5.")
        maxDepth: Int = 5,
        @McpDescription("Hard cap on the total node count. Default 200.")
        maxNodes: Int = 200,
    ): TypeHierarchyResponse {
        require(direction == "up" || direction == "down" || direction == "both") {
            "direction must be one of: up, down, both — got '$direction'"
        }
        require(scope == "file" || scope == "project" || scope == "all") {
            "scope must be one of: file, project, all — got '$scope'"
        }
        require(maxDepth in 1..20) { "maxDepth must be in 1..20" }
        require(maxNodes in 1..5_000) { "maxNodes must be in 1..5000" }
        require(target != null || (offset != null || (line != null && column != null))) {
            "Provide either `target` (FQN) or a position (offset OR line+column)."
        }

        val project = requireProject()
        return readActionBlocking {
            val resolvedPsiFile: PsiFile?
            val resolvedOffset: Int?
            if (target.isNullOrBlank()) {
                val rf = resolveFile(project, fileUrl)
                resolvedPsiFile = rf.psiFile
                resolvedOffset = resolveOffset(rf.document, offset, line, column)
            } else {
                resolvedPsiFile = null
                resolvedOffset = null
            }
            DumbService.getInstance(project).computeWithAlternativeResolveEnabled<TypeHierarchyResponse, RuntimeException> {
                try {
                    PsiHierarchyResolver.typeHierarchy(
                        project = project,
                        target = target,
                        psiFile = resolvedPsiFile,
                        offset = resolvedOffset,
                        direction = direction,
                        scopeKind = scope,
                        maxDepth = maxDepth,
                        maxNodes = maxNodes,
                    )
                } catch (e: PsiHierarchyResolver.NoTargetException) {
                    throw McpExpectedError(e.message ?: "No class at position", JsonObject(emptyMap()))
                }
            }
        }
    }

    @McpTool(name = "psi.get_outline")
    @McpDescription(
        """
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
        """
    )
    suspend fun psi_get_outline(
        @McpDescription("VFS URL of the file. null → active editor tab.")
        fileUrl: String? = null,
        @McpDescription("Include fields/properties. Default true.")
        includeFields: Boolean = true,
        @McpDescription("Include inherited members. Default false.")
        includeInherited: Boolean = false,
        @McpDescription("Max outline depth. Default 6.")
        maxDepth: Int = 6,
        @McpDescription("Hard cap on outline nodes. Default 500.")
        maxNodes: Int = 500,
    ): GetOutlineResponse {
        require(maxDepth in 1..50) { "maxDepth must be in 1..50" }
        require(maxNodes in 1..10_000) { "maxNodes must be in 1..10000" }

        val project = requireProject()
        return readActionBlocking {
            val (psiFile, vf, _) = resolveFile(project, fileUrl)
            val resp = PsiOutlineCollector.collect(
                psiFile = psiFile,
                includeFields = includeFields,
                includeInherited = includeInherited,
                maxDepth = maxDepth,
                maxNodes = maxNodes,
            )
            if (resp.fileUrl.isEmpty()) resp.copy(fileUrl = vf.url) else resp
    @McpTool(name = "psi.goto_implementation")
    @McpDescription(
        """
        |Returns every concrete implementation / override of the symbol at a position —
        |interfaces and abstract classes resolve to concrete extenders, abstract /
        |interface methods resolve to concrete overrides. Equivalent to IntelliJ's
        |Ctrl+Alt+B "Goto Implementation".
        |
        |Use this when:
        |  - You see an interface / abstract method and need the concrete implementors.
        |  - The agent is tracing a call graph through an abstraction boundary.
        |  - You want "what overrides this method?" without the noise of usages /
        |    reference sites that psi.find_usages would return.
        |
        |Do NOT use this when:
        |  - You want call sites of a method — use psi.find_usages.
        |  - You want a multi-level type tree — use psi.type_hierarchy.
        |  - The caret is on a concrete final method — there are no overrides.
        |
        |Position: pass `offset` OR `line`+`column`. The caret may be on a class /
        |interface decl or ref (returns subclasses / implementors), or on a method decl
        |or call site (returns overriding methods). Reported in `target.kind`
        |("class" | "method").
        |
        |Scope (default "project"): "project" (default — matches Ctrl+Alt+B), "all"
        |(includes library sources; on JDK / platform symbols can saturate the 10s
        |timeout — warning appended), "file" (rare).
        |
        |Returns: { target: ImplementationTarget, implementations: ImplementationInfo[],
        |scope, total, truncated, warnings[] }. Sorted by (fileUrl, range). For method
        |targets `signature` uses the *erasure* shown in the overrider's source — no
        |cross-boundary generic unification.
        |
        |Examples:
        |  fileUrl=null, line=10, column=18         — overrides of method at row 10
        |  fileUrl=null, line=5,  column=12         — implementors of interface at row 5
        |  scope="all", maxResults=50               — include library overrides, capped
        """
    )
    suspend fun psi_goto_implementation(
        @McpDescription("VFS URL of the file. null → active editor tab.")
        fileUrl: String? = null,
        @McpDescription("Document offset of the caret. Alternative to line+column.")
        offset: Int? = null,
        @McpDescription("1-based line number. Alternative to `offset`.")
        line: Int? = null,
        @McpDescription("1-based column number. Pair with `line`.")
        column: Int? = null,
        @McpDescription("\"project\" (default — matches Ctrl+Alt+B) / \"all\" (slow on hot symbols) / \"file\" (rare).")
        scope: String = "project",
        @McpDescription("Hard cap on returned implementations. Default 200.")
        maxResults: Int = 200,
    ): GotoImplementationResponse {
        require(scope == "file" || scope == "project" || scope == "all") {
            "scope must be one of: file, project, all — got '$scope'"
        }
        require(maxResults in 1..5_000) { "maxResults must be in 1..5000" }

        val project = requireProject()
        return readActionBlocking {
            val (psiFile, _, document) = resolveFile(project, fileUrl)
            val pos = resolveOffset(document, offset, line, column)
            DumbService.getInstance(project).computeWithAlternativeResolveEnabled<GotoImplementationResponse, RuntimeException> {
                try {
                    PsiHierarchyResolver.gotoImplementation(
                        project = project,
                        psiFile = psiFile,
                        offset = pos,
                        scopeKind = scope,
                        maxResults = maxResults,
                    )
                } catch (e: PsiHierarchyResolver.NoTargetException) {
                    throw McpExpectedError(e.message ?: "No class/method at position", JsonObject(emptyMap()))
                }
            }
        }
    }

    // ---------- helpers ----------

    private fun describeOpenFile(
        vf: VirtualFile,
        psiMgr: PsiManager,
        docMgr: PsiDocumentManager,
        isActive: Boolean,
    ): OpenFileInfo? {
        val psiFile = psiMgr.findFile(vf) ?: return null
        val doc = docMgr.getDocument(psiFile)
        val viewProvider = psiFile.viewProvider
        val languages = viewProvider.languages.map { it.id }.sorted()
        return OpenFileInfo(
            path = vf.path,
            url = vf.url,
            fileType = psiFile.fileType.name,
            viewProviderLanguages = languages,
            length = doc?.textLength ?: psiFile.textLength,
            caretOffset = if (isActive) caretOffsetOf(psiFile.project, vf) else null,
        )
    }

    private fun caretOffsetOf(project: Project, vf: VirtualFile): Int? = try {
        onEdtBlocking {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null && editor.virtualFile == vf) editor.caretModel.offset else null
        }
    } catch (_: Throwable) {
        null
    }

    private data class ResolvedFile(val psiFile: PsiFile, val virtualFile: VirtualFile, val document: Document?)

    private fun resolveFile(project: Project, fileUrl: String?): ResolvedFile {
        val vf: VirtualFile = if (fileUrl != null) {
            VirtualFileManager.getInstance().findFileByUrl(fileUrl)
                ?: throw McpExpectedError("No file at url: $fileUrl", JsonObject(emptyMap()))
        } else {
            FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                ?: throw McpExpectedError(
                    "No active editor tab. Open a file first, or pass fileUrl from psi.list_open_files.",
                    JsonObject(emptyMap())
                )
        }
        val psiFile = PsiManager.getInstance(project).findFile(vf)
            ?: throw McpExpectedError("File has no PSI: ${vf.url}", JsonObject(emptyMap()))
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        return ResolvedFile(psiFile, vf, document)
    }

    private fun resolveOffset(document: Document?, offset: Int?, line: Int?, column: Int?): Int =
        PositionResolver.resolveOffset(document, offset, line, column)

    private suspend fun requireProject(): Project = currentCoroutineContext().projectOrNull
        ?: throw McpExpectedError(
            "No focused project. Open a project in this IDE first (psi.* tools operate on an open editor).",
            JsonObject(emptyMap())
        )
}
