package com.github.xepozz.introspectorplugin.tools

import com.github.xepozz.introspectorplugin.core.ClassSourceResolver
import com.github.xepozz.introspectorplugin.model.AttachSourcesResponse
import com.github.xepozz.introspectorplugin.model.ClassSourceState
import com.github.xepozz.introspectorplugin.model.FindClassResponse
import com.github.xepozz.introspectorplugin.model.GetSourceResponse
import com.github.xepozz.introspectorplugin.model.ListMembersResponse
import com.github.xepozz.introspectorplugin.model.MemberInfo
import com.github.xepozz.introspectorplugin.model.ParameterInfo
import com.github.xepozz.introspectorplugin.util.onEdtBlocking
import com.github.xepozz.introspectorplugin.util.readActionBlocking
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiMethod
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject

/**
 * `code.*` — find a class by FQN, return its source text (real / attached / decompiled / stubs),
 * list its members, and trigger built-in actions that download library sources via the active
 * build system (Maven, Gradle).
 *
 * Registered from `java-introspect.xml`, which depends optionally on `com.intellij.modules.java`.
 * In non-Java IDEs (PyCharm CE, RubyMine) this toolset is not loaded at all.
 */
class CodeSourceToolset : McpToolset {

    @McpTool(name = "code.find_class")
    @McpDescription(
        """
        |Resolves a fully-qualified class name within a project and reports what level of source
        |is available — without returning the (potentially huge) text. Cheap probe before
        |code.get_source / code.list_members.
        |
        |Use this when: an agent asks "does this project see class X?", "is the source for
        |com.acme.Foo available?", "where does Bar live?". Returns enough metadata
        |(kind, modifiers, super/interfaces, member counts) to plan the next call.
        |
        |Do NOT use this when: you already need the text (call code.get_source directly), or
        |you want to enumerate members (code.list_members is the right entry point).
        |
        |Returns: { state, found, metadata?, location?, hint? } where state is one of
        |  - "SOURCE"           — real .java/.kt in a module source root
        |  - "ATTACHED_SOURCE"  — library .class but a sources jar is attached; code.get_source
        |                          will return the real source
        |  - "DECOMPILED"       — library .class without sources, but Fernflower can fill in
        |                          method bodies; code.get_source returns decompiler output
        |  - "STUBS_ONLY"       — library .class without sources and without a decompiler — only
        |                          declarations are available; consider code.attach_sources
        |  - "NOT_FOUND"        — FQN doesn't resolve in this project's scope
        |
        |Examples:
        |  fqn="java.util.HashMap"            — typically DECOMPILED (JDK stubs) or
        |                                       ATTACHED_SOURCE (JBR sources)
        |  fqn="com.acme.MyService"           — SOURCE for project code
        |  fqn="org.example.lib.Internal"     — DECOMPILED for third-party jars
        """
    )
    suspend fun code_find_class(
        @McpDescription("Fully-qualified class name. Inner classes use '$' or '.' separator (e.g. 'java.util.Map\$Entry' or 'java.util.Map.Entry').")
        fqn: String,
    ): FindClassResponse {
        val project = requireProject()
        return readActionBlocking {
            val res = ClassSourceResolver.resolve(project, normalizeFqn(fqn))
            if (res.state == ClassSourceState.NOT_FOUND || res.psiClass == null) {
                FindClassResponse(state = ClassSourceState.NOT_FOUND.name, found = false)
            } else {
                val hint = when (res.state) {
                    ClassSourceState.STUBS_ONLY -> "No decompiler is registered for this file " +
                        "and no sources are attached. Try code.attach_sources to download via Maven/Gradle, " +
                        "or install the bundled 'Java Bytecode Decompiler' plugin."
                    ClassSourceState.DECOMPILED -> "Sources are not attached. Method bodies in " +
                        "code.get_source come from the bytecode decompiler. " +
                        "Call code.attach_sources to fetch the real ones."
                    else -> null
                }
                FindClassResponse(
                    state = res.state.name,
                    found = true,
                    metadata = ClassSourceResolver.metadataOf(res.psiClass),
                    location = ClassSourceResolver.locationOf(project, res),
                    hint = hint,
                )
            }
        }
    }

    @McpTool(name = "code.get_source")
    @McpDescription(
        """
        |Returns the source text of a class. Picks the best available representation for the
        |agent in this order: real source > attached library source > decompiled bytecode >
        |stubs. The `state` field tells you which one you got.
        |
        |Use this when: you need to read the code of a class — to explain behaviour, find a
        |method body, audit usage. Whatever IntelliJ shows in the editor for that class, this
        |tool returns the same text.
        |
        |Do NOT use this when:
        |  - You only need to know IF the class exists (cheaper: code.find_class).
        |  - You only need a list of methods/fields (cheaper: code.list_members; for large
        |    classes the full text can be 100KB+).
        |
        |Returns: { state, fqn, location, text, byteLength, truncated, decompilerClass? }.
        |  - `text` is truncated to maxBytes UTF-8 bytes — `truncated=true` means there's more.
        |  - `decompilerClass` is set when state=DECOMPILED (typically
        |    "org.jetbrains.java.decompiler.IdeaDecompiler").
        |
        |Examples:
        |  fqn="java.util.HashMap"            — full Fernflower-decompiled source or attached JDK source
        |  fqn="com.acme.MyService"           — module source verbatim
        """
    )
    suspend fun code_get_source(
        @McpDescription("Fully-qualified class name.")
        fqn: String,
        @McpDescription("Maximum UTF-8 byte count of the returned text. Default 64 KiB. Reduce for large library classes.")
        maxBytes: Int = 64 * 1024,
    ): GetSourceResponse {
        require(maxBytes in 1..(2 * 1024 * 1024)) { "maxBytes must be in 1..2MiB, got $maxBytes" }
        val project = requireProject()
        return readActionBlocking {
            val res = ClassSourceResolver.resolve(project, normalizeFqn(fqn))
            if (res.state == ClassSourceState.NOT_FOUND || res.textFile == null) {
                throw McpExpectedError("Class not found: $fqn", JsonObject(emptyMap()))
            }
            // PsiFile.getText() handles all four states transparently — for .class files
            // ClsFileImpl routes through the registered Light decompiler.
            val full = res.textFile.text ?: ""
            val (truncatedText, byteLen, truncated) = truncateUtf8(full, maxBytes)
            GetSourceResponse(
                state = res.state.name,
                fqn = res.psiClass?.qualifiedName ?: fqn,
                location = ClassSourceResolver.locationOf(project, res),
                text = truncatedText,
                byteLength = byteLen,
                truncated = truncated,
                decompilerClass = res.decompilerClass,
            )
        }
    }

    @McpTool(name = "code.list_members")
    @McpDescription(
        """
        |Lists methods, constructors, fields, and inner classes of a class as a structured array
        |— without returning the full source text. For large library classes this is an order
        |of magnitude cheaper than code.get_source.
        |
        |Use this when: you want to know a class's API surface — "what methods does HashMap
        |expose?", "what fields does ProjectFileIndex declare?". Works on both source and
        |compiled (decompiled / stubs) classes.
        |
        |Do NOT use this when: you need actual implementation bodies — call code.get_source for
        |that. For stubs-only classes the signatures here are accurate but method bodies don't
        |exist anywhere.
        |
        |Returns: { fqn, state, members, total } where each member has kind ("method" |
        |"constructor" | "field" | "innerClass"), name, signature (one-line declaration as
        |IntelliJ shows in Structure view), modifiers, and for methods returnType + parameters.
        |
        |Examples:
        |  fqn="java.util.HashMap", kinds=["method"]           — every method, with signatures
        |  fqn="com.intellij.openapi.project.Project"          — every member of Project interface
        """
    )
    suspend fun code_list_members(
        @McpDescription("Fully-qualified class name.")
        fqn: String,
        @McpDescription("Restrict to these kinds. Default all. Allowed: 'method', 'constructor', 'field', 'innerClass'.")
        kinds: List<String> = listOf("method", "constructor", "field", "innerClass"),
        @McpDescription("Include inherited members. Default false (declared only).")
        includeInherited: Boolean = false,
        @McpDescription("Cap on returned members. Default 500.")
        limit: Int = 500,
    ): ListMembersResponse {
        val project = requireProject()
        val allowed = kinds.toSet()
        return readActionBlocking {
            val res = ClassSourceResolver.resolve(project, normalizeFqn(fqn))
            if (res.state == ClassSourceState.NOT_FOUND || res.psiClass == null) {
                throw McpExpectedError("Class not found: $fqn", JsonObject(emptyMap()))
            }
            val cls = res.psiClass
            val all = mutableListOf<MemberInfo>()
            if ("constructor" in allowed || "method" in allowed) {
                val methods = if (includeInherited) cls.allMethods else cls.methods
                methods.mapTo(all) { method ->
                    val isCtor = method.isConstructor
                    val kind = if (isCtor) "constructor" else "method"
                    if (kind !in allowed) return@mapTo MemberInfo("skipped", "", "")
                    MemberInfo(
                        kind = kind,
                        name = method.name,
                        signature = methodSignature(method),
                        modifiers = methodModifiers(method),
                        returnType = if (isCtor) null else method.returnType?.canonicalText,
                        parameters = method.parameterList.parameters.map {
                            ParameterInfo(it.name, it.type.canonicalText)
                        },
                    )
                }
            }
            if ("field" in allowed) {
                val fields = if (includeInherited) cls.allFields else cls.fields
                fields.mapTo(all) { field ->
                    MemberInfo(
                        kind = "field",
                        name = field.name,
                        signature = "${field.type.canonicalText} ${field.name}",
                        modifiers = fieldModifiers(field.modifierList),
                    )
                }
            }
            if ("innerClass" in allowed) {
                val inners = if (includeInherited) cls.allInnerClasses else cls.innerClasses
                inners.mapTo(all) { inner ->
                    MemberInfo(
                        kind = "innerClass",
                        name = inner.name ?: "<anonymous>",
                        signature = inner.qualifiedName ?: inner.name ?: "<anonymous>",
                        modifiers = fieldModifiers(inner.modifierList),
                    )
                }
            }
            val filtered = all.filter { it.kind != "skipped" }
            ListMembersResponse(
                fqn = cls.qualifiedName ?: fqn,
                state = res.state.name,
                members = filtered.take(limit),
                total = filtered.size,
            )
        }
    }

    @McpTool(name = "code.attach_sources")
    @McpDescription(
        """
        |Triggers the IDE's built-in action(s) to download sources for the library that owns the
        |given class. Best-effort wrapper around ActionManager — the available action depends on
        |the build system (Maven, Gradle, plain libraries).
        |
        |Use this when: code.find_class returned state=DECOMPILED or state=STUBS_ONLY and you
        |want the real sources. The action is asynchronous — once triggered, the IDE downloads
        |in the background and the next code.get_source call will return ATTACHED_SOURCE.
        |
        |Do NOT use this when:
        |  - The class is already a module source (SOURCE) or has attached sources
        |    (ATTACHED_SOURCE) — there is nothing to download.
        |  - The project isn't Maven/Gradle (the action set is empty for plain libraries).
        |
        |Returns: { outcome, fqn, triggeredAction?, candidatesTried, libraryUrl?, message }
        |where outcome is "triggered" / "no_action_available" / "not_compiled".
        |
        |Caveats:
        |  - The action is fire-and-forget; download progress isn't reported here. Re-poll with
        |    code.find_class after a few seconds.
        |  - For Gradle projects the IDE may need a project reload (Maven.DownloadSources alone
        |    won't help). If "triggered" doesn't take effect, consider exec.execute_kotlin_in_ide
        |    to flip GradleSettings.isDownloadSources=true and reload.
        """
    )
    suspend fun code_attach_sources(
        @McpDescription("Fully-qualified class name whose library should get sources downloaded.")
        fqn: String,
    ): AttachSourcesResponse {
        val project = requireProject()
        val resolution = readActionBlocking {
            ClassSourceResolver.resolve(project, normalizeFqn(fqn))
        }
        if (resolution.state == ClassSourceState.NOT_FOUND) {
            throw McpExpectedError("Class not found: $fqn", JsonObject(emptyMap()))
        }
        if (resolution.state == ClassSourceState.SOURCE ||
            resolution.state == ClassSourceState.ATTACHED_SOURCE
        ) {
            return AttachSourcesResponse(
                outcome = "not_compiled",
                fqn = fqn,
                message = "Class is already ${resolution.state.name} — nothing to download.",
            )
        }

        val classV = resolution.classVFile
        val jarUrl = classV?.let {
            VfsUtilCore.getRootFile(it).takeIf { root -> root.fileSystem.protocol == "jar" }?.url
        }
        val module = classV?.let { f -> readActionBlocking { ModuleUtilCore.findModuleForFile(f, project) } }

        // Candidate action ids covering the bundled build-system integrations. Tried in order;
        // the first one that exists AND remains enabled under the assembled data context wins.
        val candidates = listOf(
            "Maven.DownloadAllSourcesAndDocs",
            "Maven.DownloadSources",
            "ExternalSystem.DownloadSources",
            "Gradle.DownloadSources",
        )

        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .apply { if (module != null) add(LangDataKeys.MODULE, module) }
            .apply { if (classV != null) add(CommonDataKeys.VIRTUAL_FILE, classV) }
            .build()

        val mgr = ActionManager.getInstance()
        val tried = mutableListOf<String>()
        var triggered: String? = null

        // Invoking an action must happen on the EDT; the action's own update/actionPerformed
        // may pop a dialog or kick off a background task.
        onEdtBlocking {
            for (id in candidates) {
                val action = mgr.getAction(id) ?: continue
                tried.add(id)
                val presentation = action.templatePresentation.clone()
                val event = AnActionEvent.createEvent(
                    action, dataContext, presentation, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null
                )
                action.update(event)
                if (!event.presentation.isEnabled) continue
                ActionUtil.performAction(action, event)
                triggered = id
                break
            }
        }

        return if (triggered != null) {
            AttachSourcesResponse(
                outcome = "triggered",
                fqn = fqn,
                triggeredAction = triggered,
                candidatesTried = tried,
                libraryUrl = jarUrl,
                message = "Action '$triggered' dispatched. Re-poll code.find_class in a few " +
                    "seconds — downloads run on background threads.",
            )
        } else {
            AttachSourcesResponse(
                outcome = "no_action_available",
                fqn = fqn,
                candidatesTried = tried,
                libraryUrl = jarUrl,
                message = "No source-download action is registered/enabled. " +
                    "Project likely isn't Maven/Gradle, or the library isn't managed by a " +
                    "build system. Attach sources manually via File > Project Structure > Libraries.",
            )
        }
    }

    private suspend fun requireProject(): Project = currentCoroutineContext().projectOrNull
        ?: throw McpExpectedError(
            "No focused project. Open a project in this IDE first (code.* tools resolve FQNs against a project's classpath).",
            JsonObject(emptyMap())
        )

    /** Accept both java.util.Map.Entry and java.util.Map\$Entry — findClass wants `$`. */
    private fun normalizeFqn(fqn: String): String = fqn.trim()

    private fun methodSignature(m: PsiMethod): String {
        val params = m.parameterList.parameters.joinToString(", ") {
            "${it.type.canonicalText} ${it.name}"
        }
        val ret = m.returnType?.canonicalText?.let { "$it " } ?: ""
        return "$ret${m.name}($params)"
    }

    private fun methodModifiers(m: PsiMethod): List<String> {
        val ml = m.modifierList
        return listOf(
            "public", "protected", "private", "static", "abstract",
            "final", "synchronized", "native", "default",
        ).filter { ml.hasModifierProperty(it) }
    }

    private fun fieldModifiers(ml: com.intellij.psi.PsiModifierList?): List<String> {
        if (ml == null) return emptyList()
        return listOf(
            "public", "protected", "private", "static", "final", "volatile", "transient",
        ).filter { ml.hasModifierProperty(it) }
    }

    /** Truncates [s] to at most [maxBytes] UTF-8 bytes, preserving full code points. */
    private fun truncateUtf8(s: String, maxBytes: Int): Triple<String, Int, Boolean> {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return Triple(s, bytes.size, false)
        // Find the last char boundary at or before maxBytes; UTF-8 continuation bytes start with 10xxxxxx.
        var cut = maxBytes
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        val truncated = String(bytes, 0, cut, Charsets.UTF_8)
        return Triple(truncated, bytes.size, true)
    }
}
