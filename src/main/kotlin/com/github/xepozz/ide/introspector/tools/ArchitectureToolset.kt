package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.ListenerInspector
import com.github.xepozz.ide.introspector.core.ActionInventory
import com.github.xepozz.ide.introspector.core.ExtensionPointInspector
import com.github.xepozz.ide.introspector.core.PluginInventory
import com.github.xepozz.ide.introspector.core.RequirementsAnalyzer
import com.github.xepozz.ide.introspector.model.CheckRequirementsResponse
import com.github.xepozz.ide.introspector.model.ExtensionInfo
import com.github.xepozz.ide.introspector.model.ListActionsResponse
import com.github.xepozz.ide.introspector.model.ExtensionPointDetails
import com.github.xepozz.ide.introspector.model.ListExtensionPointsResponse
import com.github.xepozz.ide.introspector.model.ListExtensionsResponse
import com.github.xepozz.ide.introspector.model.ListListenersResponse
import com.github.xepozz.ide.introspector.model.ListPluginsResponse
import com.github.xepozz.ide.introspector.model.ListServicesResponse
import com.github.xepozz.ide.introspector.model.PluginDetails
import com.github.xepozz.ide.introspector.util.PositionResolver
import com.github.xepozz.ide.introspector.util.readActionBlocking
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject

class ArchitectureToolset : McpToolset {

    @McpTool(name = "arch.list_extension_points")
    @McpDescription(
        """
        |Enumerates Extension Points (EPs) live from this specific IDE instance — bundled
        |platform EPs plus any contributed by installed plugins. Reflects the *actual* running
        |state, not what the docs or Marketplace say, so disabled plugins / dev builds /
        |custom installs are accurate.
        |
        |Use this when: a user (or a plugin-development task) asks "what extension points are
        |available?", "is there a hook for X?", "what EPs does plugin Y own?". This is the
        |entry point for plugin-architecture exploration.
        |
        |Follow-up tools:
        |  - arch.list_extensions_for_ep   — who has plugged into a given EP
        |  - arch.find_extenders_of        — reverse search by EP name or class
        |  - arch.get_plugin_details       — full inventory for one plugin
        |
        |Do NOT use this when: you need to count installed plugins (use arch.list_plugins), or
        |when looking for classes that implement an interface unrelated to EPs (the IDE doesn't
        |index them that way — try arch.find_extenders_of with targetKind="interface").
        |
        |Returns: { extensionPoints: ExtensionPointInfo[], total: int } where each EP carries
        |name, kind ('INTERFACE'|'BEAN_CLASS'), interfaceOrBeanClass (FQCN), declaredByPluginId,
        |declaredByPluginName, isDynamic, extensionsCount, area ('application'|'project').
        |
        |Vanilla IDEA Community has ≥1000 EPs at application area — narrow with nameContains
        |("toolWindow", "configurable", etc.) before reading the response.
        |
        |Examples:
        |  nameContains="toolWindow"                              — every tool-window EP
        |  area="project", declaredByPlugin="com.intellij"        — project-area platform EPs
        |  nameContains="inspection", onlyDynamic=true            — dynamic inspection EPs only
        """
    )
    suspend fun arch_list_extension_points(
        @McpDescription("'application' (most common), 'project' (per-project EPs), or 'both'. Default 'application'.")
        area: String = "application",
        @McpDescription("Restrict to EPs whose declaring plugin id matches exactly, e.g. 'com.intellij' or 'com.jetbrains.php'.")
        declaredByPlugin: String? = null,
        @McpDescription("Case-insensitive substring filter on EP name, e.g. 'toolWindow' or 'configurable'. Strongly recommended — full list can be 1000+.")
        nameContains: String? = null,
        @McpDescription("Restrict to EPs marked dynamic=true (hot-swappable via dynamic plugins).")
        onlyDynamic: Boolean = false,
        @McpDescription("Cap on returned EPs. Default 500.")
        limit: Int = 500,
    ): ListExtensionPointsResponse {
        val all = PluginInventory.getInstance().extensionPoints()
            .filter { area == "both" || it.area == area }
            .filter { declaredByPlugin == null || it.declaredByPluginId == declaredByPlugin }
            .filter { nameContains == null || it.name.contains(nameContains, ignoreCase = true) }
            .filter { !onlyDynamic || it.isDynamic }
        return ListExtensionPointsResponse(all.take(limit), all.size)
    }

    @McpTool(name = "arch.list_extensions_for_ep")
    @McpDescription(
        """
        |Lists every extension registered against one specific Extension Point — i.e. every
        |plugin's contribution to that hook. For each extension you get the user's
        |implementation class, the contributing plugin, and all XML attributes from the
        |original declaration (factoryClass, anchor, id, …).
        |
        |Use this when: you've identified an EP of interest (via arch.list_extension_points)
        |and want to see who plugs into it — e.g. "what tool windows are registered?", "what
        |inspections does plugin X add?".
        |
        |Do NOT use this when: you want extensions across multiple EPs by some other criterion
        |(use arch.find_extenders_of or arch.get_plugin_details).
        |
        |Returns: { extensions: ExtensionInfo[], total: int } where each ExtensionInfo has
        |extensionPointName, implementationClass (bean class for BEAN_CLASS EPs, user class
        |for INTERFACE EPs), effectiveClass (user's actual class extracted from XML
        |attributes like factoryClass/instance/serviceImplementation/implementation),
        |providedByPluginId, providedByPluginName, additionalAttributes (full XML attribute
        |map: id, anchor, icon, etc.).
        |
        |Examples:
        |  extensionPointName="com.intellij.toolWindow"                  — every registered ToolWindowFactory with anchor/id/icon/plugin
        |  extensionPointName="com.intellij.applicationConfigurable"     — every settings panel contributed by any plugin
        """
    )
    suspend fun arch_list_extensions_for_ep(
        @McpDescription("Fully qualified EP name as returned by arch.list_extension_points (e.g. 'com.intellij.toolWindow', 'com.intellij.applicationConfigurable').")
        extensionPointName: String,
        @McpDescription("Cap on returned extensions. Default 200.")
        limit: Int = 200,
    ): ListExtensionsResponse {
        val list = PluginInventory.getInstance().extensionsForEpLive(extensionPointName, limit)
        return ListExtensionsResponse(list, list.size)
    }

    @McpTool(name = "arch.get_extension_point_details")
    @McpDescription(
        """
        |Returns the full descriptor for ONE Extension Point: kind, bean class XML schema
        |(for BEAN_CLASS EPs) or interface method signatures (for INTERFACE EPs), declared-in
        |plugin, area, and dynamic flag. This is the "how do I plug into EP X?" tool — it
        |surfaces every @Attribute / @Property / @Tag / @RequiredElement annotation on the
        |bean so an agent can generate a correct <extension> XML snippet without grepping
        |IntelliJ Community sources.
        |
        |Use this when: a user asks "what fields does ToolWindowEP take?", "is `id` required
        |on com.intellij.applicationConfigurable?", "what methods do I implement for EP X?",
        |or you've identified an EP via arch.list_extension_points and need to scaffold an
        |extension for it.
        |
        |Do NOT use this when: you want every EP at once (arch.list_extension_points), the
        |list of existing contributors (arch.list_extensions_for_ep), or the source of a
        |specific implementation class (code.get_class_source).
        |
        |Returns: ExtensionPointDetails { name, kind ('INTERFACE'|'BEAN_CLASS'),
        |interfaceOrBeanClass (FQCN), declaredByPluginId, declaredByPluginName, dynamic,
        |area ('application'|'project'), beanSchema?: { className, fields: [{ name,
        |xmlAttributeName, xmlTagName, type, required, defaultValue, deprecated }] },
        |interfaceMethods?: [{ name, signature, returnType, deprecated }], registeredCount?: int }.
        |Returns null when the EP name is not registered in any open area.
        |
        |Examples:
        |  name="com.intellij.toolWindow"                                        — bean schema for ToolWindowEP (id/anchor/factoryClass/icon…)
        |  name="com.intellij.applicationConfigurable", includeRegisteredCount=true — Configurable EP schema + how many are registered
        |  name="com.intellij.codeInsight.lineMarkerProvider"                    — INTERFACE EP — lists LineMarkerProvider methods
        """
    )
    suspend fun arch_get_extension_point_details(
        @McpDescription("Fully qualified EP name as returned by arch.list_extension_points, e.g. 'com.intellij.toolWindow', 'com.intellij.applicationConfigurable'. Required.")
        name: String,
        @McpDescription("For BEAN_CLASS EPs, harvest the bean class's @Attribute / @Property / @Tag / @RequiredElement annotations into beanSchema. Default true. Set false when you only need kind + declaring plugin.")
        includeBeanSchema: Boolean = true,
        @McpDescription("For INTERFACE EPs, list the extension interface's public abstract methods (signature + return type). Default true.")
        includeInterfaceMethods: Boolean = true,
        @McpDescription("Include the live adapter count (ep.size() — does NOT instantiate extensions) under registeredCount. Default false; flip on when you want the count without a follow-up arch.list_extension_points call.")
        includeRegisteredCount: Boolean = false,
        @McpDescription("Hard cap on bean fields / interface methods returned (per side). Default 200 — protects against pathological beans inheriting from heavy hierarchies.")
        maxFields: Int = 200,
    ): ExtensionPointDetails? = ExtensionPointInspector.getDetails(
        name = name,
        includeBeanSchema = includeBeanSchema,
        includeInterfaceMethods = includeInterfaceMethods,
        includeRegisteredCount = includeRegisteredCount,
        maxFields = maxFields,
    )

    @McpTool(name = "arch.list_plugins")
    @McpDescription(
        """
        |Lists every plugin installed in this IDE instance, with id, name, version, vendor,
        |bundled/enabled flags, sinceBuild/untilBuild compatibility range, declared dependencies,
        |and counts of declared EPs / registered extensions. Reflects the live PluginManager
        |state — disabled or third-party plugins included by default.
        |
        |Use this when: you need to know what's installed, find a plugin by partial name to
        |get its id, or audit version / compatibility.
        |
        |Do NOT use this when: you want what the plugin *does* (extensions, EPs) — call
        |arch.get_plugin_details with the id from this list.
        |
        |Returns: { plugins: PluginInfo[], total: int }. A typical IDEA install has 50-150
        |bundled plugins — set includeBundled=false to focus on third-party.
        |
        |Examples:
        |  includeBundled=false                                   — only third-party plugins
        |  nameOrIdContains="kotlin"                              — Kotlin-related plugins
        |  includeDisabled=true, nameOrIdContains="docker"        — find a disabled Docker plugin
        """
    )
    suspend fun arch_list_plugins(
        @McpDescription("Include plugins bundled with the IDE. Default true. Set false to focus on third-party plugins.")
        includeBundled: Boolean = true,
        @McpDescription("Include plugins the user has disabled. Default false (enabled only).")
        includeDisabled: Boolean = false,
        @McpDescription("Case-insensitive substring filter on plugin name OR plugin id.")
        nameOrIdContains: String? = null,
    ): ListPluginsResponse {
        val list = PluginInventory.getInstance().plugins()
            .filter { includeBundled || !it.isBundled }
            .filter { includeDisabled || it.isEnabled }
            .filter {
                val q = nameOrIdContains ?: return@filter true
                it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
            }
        return ListPluginsResponse(list, list.size)
    }

    @McpTool(name = "arch.get_plugin_details")
    @McpDescription(
        """
        |Returns the full inventory for one plugin: its metadata (version, vendor, deps),
        |every EP it declares, every extension it contributes (across all EPs), and optionally
        |all its action ids.
        |
        |Use this when: you want to understand what a single plugin actually does, what hooks
        |it exposes for others to extend, or what files (factories, services) it ships. Common
        |pattern: arch.list_plugins → arch.get_plugin_details (with the id).
        |
        |Do NOT use this when: you want extensions across multiple plugins
        |(arch.list_extensions_for_ep or arch.find_extenders_of), or just plugin counts
        |(arch.list_plugins).
        |
        |Returns: { plugin: PluginInfo, declaredExtensionPoints: ExtensionPointInfo[],
        |registeredExtensions: ExtensionInfo[], actions: string[] }.
        |
        |Caveats:
        |  - includeActions=false by default — populating action ids touches the action
        |    manager and can be slow for plugins with hundreds of actions (e.g. com.intellij).
        |  - For large plugins (com.intellij itself owns ~1000 EPs and contributes ~500
        |    extensions) the response can be huge — consider whether the narrower
        |    arch.list_extensions_for_ep is what you actually need.
        |
        |Examples:
        |  pluginId="com.github.xepozz.ide.introspector"                            — this plugin's inventory
        |  pluginId="org.jetbrains.kotlin", includeRegisteredExtensions=false         — only declared EPs
        |  pluginId="com.intellij.java", includeActions=true                          — full inventory + actions (slow)
        """
    )
    suspend fun arch_get_plugin_details(
        @McpDescription("Plugin id, e.g. 'com.intellij.java', 'org.jetbrains.kotlin', 'com.github.xepozz.ide.introspector'. Get ids from arch.list_plugins.")
        pluginId: String,
        @McpDescription("Include EPs this plugin declares (i.e. extensibility hooks it offers to others). Default true.")
        includeDeclaredExtensionPoints: Boolean = true,
        @McpDescription("Include extensions this plugin contributes to other plugins' EPs. Default true.")
        includeRegisteredExtensions: Boolean = true,
        @McpDescription("Include the plugin's action ids. Default false — slow on plugins with many actions (com.intellij has ~3000).")
        includeActions: Boolean = false,
    ): PluginDetails {
        val inv = PluginInventory.getInstance()
        val plugin = inv.plugins().firstOrNull { it.id == pluginId }
            ?: throw McpExpectedError("Unknown plugin id: $pluginId", JsonObject(emptyMap()))
        val declaredEps = if (includeDeclaredExtensionPoints) {
            inv.extensionPoints().filter { it.declaredByPluginId == pluginId }
        } else emptyList()
        val extensions = if (includeRegisteredExtensions) inv.extensionsByPlugin(pluginId) else emptyList()
        val actions = if (includeActions) actionsFor(pluginId) else emptyList()
        return PluginDetails(plugin, declaredEps, extensions, actions)
    }

    @McpTool(name = "arch.find_extenders_of")
    @McpDescription(
        """
        |Reverse-lookup: "who implements / plugs into X?". Given an EP name or a fully-qualified
        |class, returns every extension that registers against it. Use targetKind="auto" (default)
        |to let the tool decide — it first tries the target as an EP name; if no such EP exists,
        |it scans all EPs for extensions whose implementationClass matches.
        |
        |Use this when: a user asks "how is X done in IntelliJ?", "who provides Y?", "what plugin
        |adds the database tool window?" — i.e. starting from a known interface/EP and looking
        |for concrete implementations.
        |
        |Do NOT use this when: you already know the EP name and just want its extensions
        |(arch.list_extensions_for_ep is more direct), or you have a plugin id
        |(arch.get_plugin_details).
        |
        |Returns: { extensions: ExtensionInfo[], total: int }.
        |
        |Examples:
        |  target="com.intellij.toolWindow"                       — every ToolWindowFactory
        |  target="com.intellij.openapi.fileTypes.FileTypeFactory" — every FileTypeFactory impl
        |  target="com.intellij.codeInsight.intention.IntentionAction" — every IntentionAction impl
        """
    )
    suspend fun arch_find_extenders_of(
        @McpDescription("EP name (e.g. 'com.intellij.toolWindow') or fully-qualified class/interface name. Use the kind that matches what you have.")
        target: String,
        @McpDescription("'extension_point' (treat target as EP name), 'interface' (treat as class), or 'auto' (default — EP first, fall back to class scan).")
        targetKind: String = "auto",
    ): ListExtensionsResponse {
        val inv = PluginInventory.getInstance()
        val asEp = inv.extensionPoints().any { it.name == target }
        val kind = when (targetKind) {
            "extension_point", "interface" -> targetKind
            else -> if (asEp) "extension_point" else "interface"
        }
        val extensions: List<ExtensionInfo> = if (kind == "extension_point") {
            inv.extensionsForEpLive(target)
        } else {
            val out = mutableListOf<ExtensionInfo>()
            for (ep in inv.extensionPoints()) {
                inv.extensionsForEpLive(ep.name).forEach {
                    if (it.implementationClass == target) out.add(it)
                }
            }
            out
        }
        return ListExtensionsResponse(extensions, extensions.size)
    }

    @McpTool(name = "arch.check_lock_requirements")
    @McpDescription(
        """
        |Statically verifies that every caller of a method holds the IntelliJ read/write lock
        |the method requires. The target's @RequiresReadLock / @RequiresWriteLock /
        |@RequiresReadLockAbsence is the contract; each caller is checked by walking to the
        |enclosing method/lambda: does it carry the same annotation (transitive), or is it
        |lexically inside ReadAction.compute / runReadAction / WriteAction.run / runWriteAction?
        |Mirror of DevKit's `find_lock_requirement_usages` — works in IDEs without DevKit.
        |
        |Use this when: "is foo() always called under a read lock?", "who calls bar() without a
        |write action?", or before changing a method's lock contract.
        |
        |Do NOT use this when: you want runtime/dynamic checks (static — Runnable posted to
        |invokeLater is `unknown`, not `ok`), or the target has no annotation (response is
        |trivially empty). For "who calls foo()?" without lock analysis use psi.find_usages.
        |
        |Target: pass `target` as `FQN.method` (overload-ambiguous; every method with that
        |simple name is included), OR `fileUrl` + offset / line+column on the method
        |declaration — same position semantics as psi.find_usages.
        |
        |Returns: { target, expected[], callSites[], total, truncated }. CallSiteAnalysis has
        |fileUrl, range, callerSignature, callerAnnotations[], contextHints[], status
        |('ok'|'mismatch'|'unknown'), reason. `unknown` = we can't reason statically
        |(invokeLater, reflection, coroutine builders) — agent should escalate to manual review.
        |
        |Examples:
        |  target="com.intellij.psi.PsiManager.findFile"                          — all callers
        |  fileUrl=null, line=42, column=12                                        — method at row 42 in active editor
        |  target="com.example.MyService.doStuff", scope="file"                    — same-file only
        |  target="com.example.MyService.doStuff", includeImplementations=false    — direct calls only
        """
    )
    suspend fun arch_check_lock_requirements(
        @McpDescription("FQN.method (e.g. 'com.intellij.psi.PsiManager.findFile'). Mutually exclusive with fileUrl+position.")
        target: String? = null,
        @McpDescription("VFS URL of the source file containing the target method. Use with offset OR line+column.")
        fileUrl: String? = null,
        @McpDescription("Document offset on the method's name. Alternative to line+column.")
        offset: Int? = null,
        @McpDescription("1-based line number of the method's name.")
        line: Int? = null,
        @McpDescription("1-based column number of the method's name.")
        column: Int? = null,
        @McpDescription("\"project\" (default) / \"file\" / \"all\" (library sources — slow).")
        scope: String = "project",
        @McpDescription("Also analyse callers of overrides via DefinitionsScopedSearch. Default true.")
        includeImplementations: Boolean = true,
        @McpDescription("Hard cap on returned call sites. Default 500.")
        maxCallSites: Int = 500,
    ): CheckRequirementsResponse = runRequirementsCheck(
        annotationFqns = RequirementsAnalyzer.LOCK_ANNOTATION_FQNS,
        wrapperKind = RequirementsAnalyzer.WrapperKind.LOCK,
        target = target, fileUrl = fileUrl, offset = offset, line = line, column = column,
        scope = scope, includeImplementations = includeImplementations, maxCallSites = maxCallSites,
    )

    @McpTool(name = "arch.check_threading_requirements")
    @McpDescription(
        """
        |Statically verifies that every caller of a method runs on the thread the method
        |requires. The target's @RequiresEdt / @RequiresBackgroundThread /
        |@RequiresBlockingContext annotation is the contract; each caller is checked by walking
        |to the enclosing method/lambda and asking: same annotation (transitive), or lexically
        |inside ApplicationManager.invokeLater / SwingUtilities.invokeLater (EDT-pushing) /
        |executeOnPooledThread (BGT-pushing)? Mirror of DevKit's
        |`find_threading_requirements_usages` — works in IDEs without DevKit loaded.
        |
        |Use this when: "is foo() always called from a BGT?", "who calls X from the EDT?", or
        |before tightening a threading contract.
        |
        |Do NOT use this when: you want runtime checks (ApplicationManager.isDispatchThread()),
        |or the target has no threading annotation (response trivially empty). For callers
        |without thread context, use psi.find_usages.
        |
        |Target: pass `target` as `FQN.method`, OR `fileUrl` + offset / line+column on the
        |method's name — same position semantics as psi.find_usages.
        |
        |Returns: { target, expected[], callSites[], total, truncated }. CallSiteAnalysis
        |has callerSignature, callerAnnotations[], contextHints[] (['inside-invokeLater']),
        |status ('ok'|'mismatch'|'unknown'), reason. Lambdas in opaque Runnable consumers
        |(Future, ExecutorService, kotlinx coroutines) → 'unknown'.
        |
        |Examples:
        |  target="com.intellij.openapi.editor.Editor.getCaretModel"                — @RequiresEdt callers
        |  fileUrl="…/MyService.kt", line=20, column=5, scope="file"                — one-file scope
        |  target="com.example.Backend.doWork", includeImplementations=false        — direct calls only
        """
    )
    suspend fun arch_check_threading_requirements(
        @McpDescription("FQN.method (e.g. 'com.intellij.openapi.editor.Editor.getCaretModel'). Mutually exclusive with fileUrl+position.")
        target: String? = null,
        @McpDescription("VFS URL of the source file containing the target method. Use with offset OR line+column.")
        fileUrl: String? = null,
        @McpDescription("Document offset on the method's name. Alternative to line+column.")
        offset: Int? = null,
        @McpDescription("1-based line number of the method's name.")
        line: Int? = null,
        @McpDescription("1-based column number of the method's name.")
        column: Int? = null,
        @McpDescription("\"project\" (default) / \"file\" / \"all\" (library sources — slow).")
        scope: String = "project",
        @McpDescription("Also analyse callers of overrides via DefinitionsScopedSearch. Default true.")
        includeImplementations: Boolean = true,
        @McpDescription("Hard cap on returned call sites. Default 500.")
        maxCallSites: Int = 500,
    ): CheckRequirementsResponse = runRequirementsCheck(
        annotationFqns = RequirementsAnalyzer.THREADING_ANNOTATION_FQNS,
        wrapperKind = RequirementsAnalyzer.WrapperKind.THREADING,
        target = target, fileUrl = fileUrl, offset = offset, line = line, column = column,
        scope = scope, includeImplementations = includeImplementations, maxCallSites = maxCallSites,
    )

    // ---------- shared plumbing for the two check_* tools ----------

    private suspend fun runRequirementsCheck(
        annotationFqns: Set<String>,
        wrapperKind: RequirementsAnalyzer.WrapperKind,
        target: String?,
        fileUrl: String?,
        offset: Int?,
        line: Int?,
        column: Int?,
        scope: String,
        includeImplementations: Boolean,
        maxCallSites: Int,
    ): CheckRequirementsResponse {
        require(scope == "project" || scope == "file" || scope == "all") {
            "scope must be one of: project, file, all — got '$scope'"
        }
        require(maxCallSites in 1..5_000) { "maxCallSites must be in 1..5000" }
        val hasTarget = target != null
        val hasPosition = fileUrl != null || offset != null || line != null || column != null
        if (hasTarget == hasPosition) {
            throw McpExpectedError("specify target OR fileUrl+position", JsonObject(emptyMap()))
        }

        val project = requireProject()
        return readActionBlocking {
            DumbService.getInstance(project).computeWithAlternativeResolveEnabled<CheckRequirementsResponse, RuntimeException> {
                val (psiFile, pos) = if (target == null) {
                    val (f, _, doc) = resolveFile(project, fileUrl)
                    val resolvedOffset = PositionResolver.resolveOffset(doc, offset, line, column)
                    f to resolvedOffset
                } else {
                    null to null
                }
                try {
                    RequirementsAnalyzer.analyze(
                        project = project,
                        annotationFqns = annotationFqns,
                        wrapperKind = wrapperKind,
                        target = target,
                        psiFile = psiFile,
                        offset = pos,
                        scopeKind = scope,
                        includeImplementations = includeImplementations,
                        maxCallSites = maxCallSites,
                    )
                } catch (e: RequirementsAnalyzer.TargetNotFound) {
                    throw McpExpectedError(e.message ?: "Target not found", JsonObject(emptyMap()))
                } catch (e: RequirementsAnalyzer.JavaModuleUnavailable) {
                    throw McpExpectedError(e.message ?: "Java module unavailable", JsonObject(emptyMap()))
                }
            }
        }
    }

    private data class ResolvedFile(val psiFile: PsiFile, val virtualFile: com.intellij.openapi.vfs.VirtualFile, val document: com.intellij.openapi.editor.Document?)

    private fun resolveFile(project: Project, fileUrl: String?): ResolvedFile {
        val vf: com.intellij.openapi.vfs.VirtualFile = if (fileUrl != null) {
            VirtualFileManager.getInstance().findFileByUrl(fileUrl)
                ?: throw McpExpectedError("No file at url: $fileUrl", JsonObject(emptyMap()))
        } else {
            throw McpExpectedError("fileUrl is required when target is not supplied", JsonObject(emptyMap()))
        }
        val psiFile = PsiManager.getInstance(project).findFile(vf)
            ?: throw McpExpectedError("File has no PSI: ${vf.url}", JsonObject(emptyMap()))
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        return ResolvedFile(psiFile, vf, document)
    }

    private suspend fun requireProject(): Project = currentCoroutineContext().projectOrNull
        ?: throw McpExpectedError(
            "No focused project. Open a project in this IDE first (arch.check_* tools need PSI access).",
            JsonObject(emptyMap())
        )

    @McpTool(name = "arch.list_services")
    @McpDescription(
        """
        |Enumerates IntelliJ services (application/project/module-scoped) declared by every
        |loaded plugin. Per service: serviceInterface FQN, serviceImplementation FQN, scope,
        |preload mode, test/headless/overriding implementations, and the contributing plugin id.
        |Reads from PluginDescriptor.containerDescriptor.services — never instantiates a service
        |(safe even for half-broken third-party plugins).
        |
        |Use this when: a user asks "what services does plugin X expose?", "where is the
        |implementation of service Y?", "which services are application-level vs project-level?",
        |"what preloads at startup?" — i.e. service-layer plugin-architecture exploration. This
        |is the service-shaped counterpart of arch.list_extension_points.
        |
        |Follow-up tools:
        |  - arch.get_plugin_details      — full inventory for one plugin (EPs + extensions)
        |  - arch.list_extension_points   — for non-service extensibility hooks
        |
        |Do NOT use this when: you want extension points (use arch.list_extension_points),
        |plugin metadata (arch.list_plugins / arch.get_plugin_details), or actions
        |(arch.list_actions). Service implementation classes are NOT auto-instantiated by this
        |tool: do not use it to "fetch" a service instance. @Service-annotated light services
        |registered without plugin.xml are NOT enumerated here (see arch.find_extenders_of).
        |
        |Returns: { services: ServiceInfo[], total: int } where each ServiceInfo has
        |serviceInterface (FQCN; equals serviceImplementation when XML omits the interface),
        |serviceImplementation (FQCN), scope ('application'|'project'|'module'), preload
        |('FALSE'|'TRUE'|'NOT_HEADLESS'|'NOT_LIGHT_EDIT'|'AWAIT'), overrides (boolean),
        |testServiceImplementation (FQCN or null), headlessImplementation (FQCN or null),
        |providedByPluginId, providedByPluginName.
        |
        |Vanilla IDEA Community has 1000+ services — narrow with nameContains ("Psi", "Project",
        |"Editor") and/or providedByPluginId before reading, or rely on limit (default 500).
        |
        |Examples:
        |  scope="application", nameContains="Psi"                   — application-scoped PSI services
        |  providedByPluginId="org.jetbrains.kotlin"                 — every service the Kotlin plugin registers
        |  scope="project", onlyPreloaded=true                       — project services that preload eagerly
        |  nameContains="ToolWindow", scope="all"                    — services matching ToolWindow at any scope
        """
    )
    suspend fun arch_list_services(
        @McpDescription("Service scope filter: 'application', 'project', 'module', or 'all'. Default 'all'.")
        scope: String = "all",
        @McpDescription("Restrict to services contributed by this plugin id (e.g. 'com.intellij', 'org.jetbrains.kotlin').")
        providedByPluginId: String? = null,
        @McpDescription("Case-insensitive substring filter on serviceInterface OR serviceImplementation FQN. Strongly recommended — IDEA ships 1000+ services.")
        nameContains: String? = null,
        @McpDescription("Include services with preload != FALSE only. Default false.")
        onlyPreloaded: Boolean = false,
        @McpDescription("Cap on returned services. Default 500.")
        limit: Int = 500,
    ): ListServicesResponse {
        val all = PluginInventory.getInstance().services()
            .filter { scope == "all" || it.scope == scope }
            .filter { providedByPluginId == null || it.providedByPluginId == providedByPluginId }
            .filter {
                val q = nameContains ?: return@filter true
                it.serviceInterface.contains(q, ignoreCase = true) ||
                    it.serviceImplementation.contains(q, ignoreCase = true)
            }
            .filter { !onlyPreloaded || it.preload != "FALSE" }
        return ListServicesResponse(all.take(limit), all.size)
    }

    @McpTool(name = "arch.list_listeners")
    @McpDescription(
        """
        |Enumerates MessageBus listeners declared in plugin.xml across all loaded plugins
        |(both <applicationListeners> and <projectListeners>). For each declaration you get
        |the topic class FQN, the listener implementation FQN, its scope, the contributing
        |plugin id, and the test-mode / headless-mode activation flags.
        |
        |Use this when: a plugin developer wants to know "who is subscribed to topic X?",
        |"what listeners does plugin Y register?", or "why isn't my listener firing — is
        |something else consuming the event first?". Also useful for auditing
        |unexpected reactions to platform topics (BulkFileListener, FileEditorManagerListener,
        |DumbService.DUMB_MODE, VirtualFileManager.VFS_CHANGES, …).
        |
        |Do NOT use this when: you want listeners registered programmatically via
        |MessageBus.connect().subscribe(...) — those are NOT declared in plugin.xml and are
        |out of scope (see the platform's MessageBusImpl for that route). Also don't use
        |this to invoke or fire events — read-only inventory only.
        |
        |Returns: { listeners: ListenerInfo[], total: int }. Each ListenerInfo carries
        |topicClass (FQN), listenerClass (FQN), scope ('application'|'project'),
        |providedByPluginId, providedByPluginName, activeInTestMode, activeInHeadlessMode.
        |Vanilla IDEA Community has ~200-400 plugin.xml-declared listeners — narrow with
        |topicContains or providedByPluginId before reading the whole response.
        |
        |Examples:
        |  topicContains="FileEditorManagerListener"             — who watches file-editor events
        |  scope="project"                                       — project-scope listeners only
        |  providedByPluginId="org.jetbrains.kotlin"             — every listener the Kotlin plugin registers
        |  topicContains="VFS_CHANGES", scope="application"      — app-scope VFS subscribers
        """
    )
    suspend fun arch_list_listeners(
        @McpDescription("Case-insensitive substring filter on the topic class FQN (e.g. 'FileEditorManagerListener', 'VFS_CHANGES', 'BulkFileListener'). Strongly recommended — full list can be 400+.")
        topicContains: String? = null,
        @McpDescription("Restrict to listeners declared by this plugin id exactly (e.g. 'com.intellij', 'org.jetbrains.kotlin'). Get ids from arch.list_plugins.")
        providedByPluginId: String? = null,
        @McpDescription("'application', 'project', or 'both'. Default 'both'.")
        scope: String = "both",
        @McpDescription("Cap on returned listeners. Default 300.")
        limit: Int = 300,
    ): ListListenersResponse {
        if (scope !in VALID_LISTENER_SCOPES) {
            throw McpExpectedError(
                "Invalid scope: '$scope'. Must be one of 'application', 'project', 'both'.",
                JsonObject(emptyMap()),
            )
        }
        val clampedLimit = limit.coerceIn(1, 5_000)
        val all = ListenerInspector.getInstance().list()
            .filter { scope == "both" || it.scope == scope }
            .filter { providedByPluginId == null || it.providedByPluginId == providedByPluginId }
            .filter { topicContains == null || it.topicClass.contains(topicContains, ignoreCase = true) }
        return ListListenersResponse(all.take(clampedLimit), all.size)
    }

    @McpTool(name = "arch.list_actions")
    @McpDescription(
        """
        |Global action catalog with fuzzy search — the JSON equivalent of Ctrl+Shift+A "Find
        |Action" across every plugin. Each result carries id, display text, description,
        |owning plugin id/name, formatted keyboard shortcut(s), the action-group path
        |(category), the icon path, and an isInternal flag.
        |
        |Use this when: you need to discover an action id to call later, find which plugin
        |owns a feature ("who provides 'Run Anything'?"), audit shortcut bindings, or list
        |all actions a specific plugin contributes. Pairs with arch.get_plugin_details for
        |the inverse direction (plugin → its actions).
        |
        |Do NOT use this when: you want to *invoke* an action (use ui.invoke_action_on or the
        |JetBrains built-in execute_action_by_id), or you already have a plugin id and want
        |that plugin's full inventory (arch.get_plugin_details(includeActions=true)). Do not
        |use this to enumerate action *groups* — groups are out of scope here, query their
        |child action ids instead.
        |
        |Returns: { actions: ActionInfo[], total: int, truncated: bool }. `total` is the
        |count BEFORE applying `limit`; `truncated=true` means either the limit was hit OR
        |the 10 s hard timeout fired and the catalog walk stopped early.
        |
        |Performance: vanilla IDEA has ~3000+ registered actions. When `query` looks like a
        |prefix (alphanumeric, no wildcards), the lookup uses ActionManagerEx.getActionIdList
        |at the registry level (no AnAction instantiation). Otherwise we stream the full id
        |list applying filters lazily and stop at `limit`. Results are cached in a TtlCache
        |keyed on (query, providedByPluginId, includeInternal) for 60 s. Even with these
        |optimisations, prefer a non-null `query` whenever possible — an unfiltered call
        |with limit=200 still walks every id.
        |
        |Examples:
        |  query="Refactor"                              — every action whose id or text contains "Refactor"
        |  query="Editor", providedByPluginId="com.intellij" — platform editor actions only
        |  providedByPluginId="org.jetbrains.kotlin"      — every Kotlin-plugin action
        |  query="Run", includeInternal=true, limit=500  — Run* actions, internal included
        """
    )
    suspend fun arch_list_actions(
        @McpDescription("Case-insensitive substring on action id + display text. When alphanumeric (regex ^[A-Za-z0-9_.]+$) triggers the registry prefix fast-path. Null = no query filter.")
        query: String? = null,
        @McpDescription("Exact match on plugin id (e.g. 'com.intellij', 'org.jetbrains.kotlin'). Restricts the candidate set via ActionManagerEx.getPluginActions — the cheapest filter when known.")
        providedByPluginId: String? = null,
        @McpDescription("Reserved — currently a NO-OP. The platform itself decides whether <action internal=\"true\"/> entries are registered (based on -Didea.is.internal): when off they never appear in the action manager and we cannot resurrect them; when on they're indistinguishable from regular actions in any public API. Kept on the signature so a future platform-level detector can be wired without breaking callers.")
        includeInternal: Boolean = false,
        @McpDescription("Cap on returned actions. Coerced into 1..2000. Default 200.")
        limit: Int = 200,
    ): ListActionsResponse {
        return ActionInventory.getInstance().listActions(
            query = query,
            providedByPluginId = providedByPluginId,
            includeInternal = includeInternal,
            limit = limit,
        )
    }

    private fun actionsFor(pluginId: String): List<String> = runCatching {
        val am = ActionManagerEx.getInstanceEx()
        val pid = PluginId.getId(pluginId)
        am.getPluginActions(pid).toList()
    }.getOrElse { emptyList() }

    companion object {
        private val VALID_LISTENER_SCOPES = setOf("application", "project", "both")
    }
}
