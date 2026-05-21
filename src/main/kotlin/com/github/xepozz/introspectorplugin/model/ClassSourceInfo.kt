package com.github.xepozz.introspectorplugin.model

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
