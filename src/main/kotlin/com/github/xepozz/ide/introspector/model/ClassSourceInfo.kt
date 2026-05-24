package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Where the text of a class came from. Mirrors the four states an IntelliJ user can be in:
 *  - SOURCE          : real .java/.kt the project owns (module source roots)
 *  - ATTACHED_SOURCE : compiled class, but the library has a sources jar attached
 *  - DECOMPILED      : compiled class, no sources, IDE has a Light decompiler (Fernflower) that
 *                      produced full method bodies
 *  - STUBS_ONLY      : compiled class, no sources, no decompiler — only signatures + `// compiled code`
 *  - NOT_FOUND       : the FQN doesn't resolve in this project's scope
 */
enum class ClassSourceState { SOURCE, ATTACHED_SOURCE, DECOMPILED, STUBS_ONLY, NOT_FOUND }

@Serializable
data class ClassLocation(
    /** File extension of the resolved file (java, kt, class). */
    val extension: String?,
    /** URL of the file the text comes from. Already-canonicalised, may be inside a jar
     *  (`jar:///…/foo.jar!/com/acme/X.class`). */
    val fileUrl: String?,
    /** When the file lives inside a jar, the URL of that jar; null for plain files. */
    val jarUrl: String? = null,
    /** Plugin/library/module name the class belongs to, if resolvable. */
    val ownerName: String? = null,
)

@Serializable
data class ClassMetadata(
    val fqn: String,
    val simpleName: String,
    /** "class" | "interface" | "enum" | "annotation" | "record". */
    val kind: String,
    val modifiers: List<String>,
    val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    val typeParameters: List<String> = emptyList(),
    val containingClass: String? = null,
    val methodCount: Int = 0,
    val fieldCount: Int = 0,
    val innerClassCount: Int = 0,
)

@Serializable
data class FindClassResponse(
    val state: String,
    val found: Boolean,
    val metadata: ClassMetadata? = null,
    val location: ClassLocation? = null,
    /** Hint to the agent when sources are missing — e.g. "call code.attach_sources to download". */
    val hint: String? = null,
)

@Serializable
data class GetSourceResponse(
    val state: String,
    val fqn: String,
    val location: ClassLocation? = null,
    /** The actual text. Truncated to `maxBytes` (in UTF-8 bytes) if the file is bigger;
     *  [truncated] reflects this. */
    val text: String,
    val byteLength: Int,
    val truncated: Boolean,
    /** When state == DECOMPILED, names the decompiler that produced the text (typically
     *  "org.jetbrains.java.decompiler.IdeaDecompiler"). */
    val decompilerClass: String? = null,
)

@Serializable
data class MemberInfo(
    /** "method" | "constructor" | "field" | "innerClass". */
    val kind: String,
    val name: String,
    val signature: String,
    val modifiers: List<String> = emptyList(),
    val returnType: String? = null,
    val parameters: List<ParameterInfo> = emptyList(),
)

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
)

@Serializable
data class ListMembersResponse(
    val fqn: String,
    val state: String,
    val members: List<MemberInfo>,
    val total: Int,
)

/**
 * Lightweight textual span for a class declaration in its source file. Offsets are
 * 0-based UTF-16 chars (matching [com.intellij.openapi.editor.Document]). When the
 * declaration's range can't be obtained (compiled-only class with no PSI text), the
 * surrounding [ClassEntry.declarationRange] is null.
 */
@Serializable
data class DeclarationRange(
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * One row in `code.list_classes_in_module` / `code.list_classes_in_package`. Top-level
 * classes only — anonymous + inner classes are excluded from v1 (use `code.list_members`
 * with `kinds=["innerClass"]` for nested types).
 *
 * `kind` follows the same vocabulary as [ClassMetadata.kind] plus the synthetic
 * `kotlinFileFacade` for `FooKt`-style top-level Kotlin function/property holders.
 *
 * `byteLength` is the textual file size (UTF-16 char count) — agents budget
 * `code.get_source` follow-ups against it. Null when the file isn't available.
 */
@Serializable
data class ClassEntry(
    val fqn: String,
    val simpleName: String,
    /** Package FQN; `""` for the default/root package. */
    val pkg: String,
    /** "class" | "interface" | "enum" | "record" | "annotation" | "kotlinFileFacade". */
    val kind: String,
    val fileUrl: String? = null,
    val declarationRange: DeclarationRange? = null,
    val byteLength: Int? = null,
)

/**
 * Response shape for both `code.list_classes_in_module` and `code.list_classes_in_package`.
 *
 * - `scope` echoes whatever single-string scope identifies the call (module name or
 *   package FQN) for trace/log readability.
 * - `total` is the unbounded count BEFORE [limit] was applied. `classes.size <= limit`.
 * - `truncated=true` when `total > limit` (results were capped).
 * - `timedOut=true` when the 10s wall-clock deadline fired; results are partial.
 * - `note` set when the project is in dumb (indexing) mode and results may be stale.
 */
@Serializable
data class ListClassesResponse(
    val scope: String,
    val classes: List<ClassEntry>,
    val total: Int,
    val truncated: Boolean,
    val timedOut: Boolean = false,
    val note: String? = null,
)

@Serializable
data class AttachSourcesResponse(
    /** "triggered" — an action was found and dispatched (download is asynchronous).
     *  "no_action_available" — no candidate action matched the build system in use.
     *  "not_compiled" — the class is already a module source / has attached sources. */
    val outcome: String,
    val fqn: String,
    /** Action id of the action that was invoked, if any. */
    val triggeredAction: String? = null,
    /** Action ids that were tried but unavailable in this IDE / project context. */
    val candidatesTried: List<String> = emptyList(),
    /** Library / jar the class belongs to, if resolved. */
    val libraryUrl: String? = null,
    val message: String,
)
