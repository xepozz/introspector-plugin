package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.ComponentRegistry
import com.github.xepozz.ide.introspector.core.ComponentSerializer
import com.github.xepozz.ide.introspector.core.ComponentTreeWalker
import com.github.xepozz.ide.introspector.core.DialogInspector
import com.github.xepozz.ide.introspector.core.ToolWindowInspector
import com.github.xepozz.ide.introspector.core.UiActionInvoker
import com.github.xepozz.ide.introspector.core.XPathMatcher
import com.github.xepozz.ide.introspector.exec.AuditLogger
import com.github.xepozz.ide.introspector.exec.UiActionBlocklist
import com.github.xepozz.ide.introspector.exec.UiActionConfirmationManager
import com.github.xepozz.ide.introspector.exec.UiActionSettings
import com.github.xepozz.ide.introspector.model.ComponentInfo
import com.github.xepozz.ide.introspector.model.ComponentProperty
import com.github.xepozz.ide.introspector.model.DialogsResponse
import com.github.xepozz.ide.introspector.model.FindComponentsResponse
import com.github.xepozz.ide.introspector.model.ToolWindowsResponse
import com.github.xepozz.ide.introspector.model.InvokeActionResponse
import com.github.xepozz.ide.introspector.model.UiTreeResponse
import com.github.xepozz.ide.introspector.util.DEFAULT_EDT_TIMEOUT_MS
import com.github.xepozz.ide.introspector.util.onEdtBlocking
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import java.awt.Component
import java.awt.Point
import java.awt.Window
import java.util.concurrent.TimeoutException
import javax.accessibility.Accessible
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * MCP toolset for UI introspection. Methods are exposed by the bundled MCP server's
 * ReflectionToolsProvider — the snake_case method name becomes the tool name when
 * [McpTool.name] is not set. We override `name` to keep the grouped `ui.*` namespace.
 *
 * All methods EXCEPT [ui_invoke_action_on] are pure read operations against the live Swing
 * tree (safe to call at any time, no side effects). [ui_invoke_action_on] is the one
 * privileged WRITE entry point — it mirrors the opt-in pattern of `ExecToolset` (off by
 * default in [UiActionSettings], per-call modal confirmation, blocklist double-prompt,
 * audited, hard 10 s timeout via `onEdtBlocking`).
 */
class UiInspectorToolset : McpToolset {

    @McpTool(name = "ui.get_tree")
    @McpDescription(
        """
        |Returns the IDE's live Swing component tree (BFS, capped by maxDepth). Each node
        |carries a stable id ("c_xxxxxxxx") you can pass to ui.get_properties or
        |screenshot.capture to drill into a specific component.
        |
        |Use this when: you need to see the current UI structure of the IDE — what windows,
        |panels, toolbars and actions are on screen right now. Typical first step before any
        |targeted ui.* call when you don't yet know the component id.
        |
        |Do NOT use this when: you already know the component id (call ui.get_properties),
        |you only need to locate one component by visible text (use ui.find_by_name —
        |much cheaper), or you have screen coordinates (use ui.find_by_coordinates).
        |
        |Defaults are tuned for cheapness: depth 12, includeProperties=false. Property
        |collection per node hits reflection on thousands of components and can saturate
        |the EDT — fetch them point-by-point via ui.get_properties instead. Hard-capped at
        |5000 nodes; if truncated the response has truncated=true and a warning.
        |
        |Scope with rootSelector: "frame" (top-level frames only), "dialog" (only modal
        |dialogs), "tool_window:<id>" (a specific tool window's content, e.g.
        |"tool_window:Project"), or null for everything visible. Narrow the scope whenever
        |you can — it's the single biggest perf knob.
        |
        |Returns: { nodes: ComponentInfo[], rootIds: string[], truncated: boolean, warnings: string[] }.
        |Each ComponentInfo has id, class (FQCN), name, accessibleName, accessibleRole,
        |bounds {x,y,width,height}, visible, enabled, text, toolTipText, properties[], children[].
        |
        |Examples:
        |  rootSelector="tool_window:Project", maxDepth=10   — Project view subtree
        |  rootSelector="frame", maxDepth=6                  — shallow frame overview
        |  rootSelector="dialog"                             — only open modal dialogs
        """
    )
    suspend fun `ui_get_tree`(
        @McpDescription("Max BFS depth. Default 12. Keep ≤15 for the full frame; full IDE trees easily exceed 5000 nodes.")
        maxDepth: Int = 12,
        @McpDescription("Scope filter: 'frame', 'dialog', 'tool_window:<id>' (e.g. 'tool_window:Project'), or null for everything visible.")
        rootSelector: String? = null,
        @McpDescription("Include invisible components (those with isVisible()==false). Off by default.")
        includeInvisible: Boolean = false,
        @McpDescription("Attach UI-Inspector-style property bag to every node. Expensive — prefer ui.get_properties for a single id.")
        includeProperties: Boolean = false,
        @McpDescription("Maximum character length for any single property value before truncation.")
        truncatePropertyValueAt: Int = 200,
    ): UiTreeResponse = onEdtBlocking {
        buildTree(maxDepth, rootSelector, includeInvisible, includeProperties, truncatePropertyValueAt)
    }

    @McpTool(name = "ui.find_by_name")
    @McpDescription(
        """
        |Locates Swing components by their text-bearing attributes: `name` (programmatic),
        |`text` (button/label caption), `accessibleName` (a11y label), `toolTipText`. Walks the
        |entire UI tree once and returns the first `limit` matches. matchMode controls the
        |comparison: "exact", "contains" (default, case-insensitive substring), or "regex".
        |
        |Use this when: you know what the component says or what its programmatic name is
        |("Run", "ProjectViewTree", "buildButton") and want its id so you can inspect or
        |screenshot it.
        |
        |Do NOT use this when: you need XML-path-style queries with class+attribute predicates
        |(use ui.find_by_xpath), or you only know screen coordinates (use
        |ui.find_by_coordinates), or you need the structural surroundings (use ui.get_tree).
        |
        |Returns: { matches: ComponentInfo[], total: int }. Matches include id you can reuse
        |with ui.get_properties / screenshot.capture(target='component').
        |
        |Examples:
        |  query="Run", matchMode="exact", searchIn=["text"]           — the Run toolbar button
        |  query="ProjectViewTree", searchIn=["name"]                  — programmatic-name match
        |  query="^Save( All)?$", matchMode="regex", searchIn=["text"] — Save / Save All buttons
        """
    )
    suspend fun `ui_find_by_name`(
        @McpDescription("Search string. Treated as substring (contains), exact text, or regex per matchMode.")
        query: String,
        @McpDescription("'exact' | 'contains' (default) | 'regex'.")
        matchMode: String = "contains",
        @McpDescription("Case sensitivity for 'exact'/'contains'. Ignored for 'regex' (use (?i) for case-insensitive regex).")
        caseSensitive: Boolean = false,
        @McpDescription("Which fields to test. Default ['name','text','accessibleName','toolTipText']. Narrow it to speed things up.")
        searchIn: List<String> = DEFAULT_SEARCH_FIELDS,
        @McpDescription("Cap on returned matches. Default 50.")
        limit: Int = 50,
    ): FindComponentsResponse = onEdtBlocking {
        findByName(query, matchMode, caseSensitive, searchIn, limit)
    }

    @McpTool(name = "ui.find_by_coordinates")
    @McpDescription(
        """
        |Returns the deepest visible component at point (x, y) — equivalent to clicking that
        |pixel and seeing which Swing component would receive the event. With
        |returnAncestors=true, also walks up the parent chain so you can see container context.
        |
        |Use this when: you have screen coordinates (e.g. from a screenshot, a click log, or
        |the user pointing at a spot) and need the component there.
        |
        |Do NOT use this when: you know the component's name or text — ui.find_by_name is
        |faster. The coordinate space matters: 'screen' (default) uses virtual-desktop
        |pixels (multi-monitor friendly); 'frame' uses the active frame's local coords.
        |
        |Returns: { matches: ComponentInfo[], total: int } where matches[0] is the deepest
        |component and (if requested) matches[1..N] are its ancestors up to the window root.
        |
        |Examples:
        |  x=42, y=80, coordinateSpace="frame"                  — what's at the frame's (42,80)
        |  x=1280, y=400, coordinateSpace="screen"              — virtual-desktop coords
        |  x=300, y=200, returnAncestors=false                  — just the deepest component
        """
    )
    suspend fun `ui_find_by_coordinates`(
        @McpDescription("X coordinate in pixels (virtual desktop if coordinateSpace=screen).")
        x: Int,
        @McpDescription("Y coordinate in pixels.")
        y: Int,
        @McpDescription("'screen' (default, virtual desktop) or 'frame' (active IDE frame, top-left origin).")
        coordinateSpace: String = "screen",
        @McpDescription("Include the parent chain from the deepest component up to the root window.")
        returnAncestors: Boolean = true,
    ): FindComponentsResponse = onEdtBlocking {
        findByCoordinates(x, y, coordinateSpace, returnAncestors)
    }

    @McpTool(name = "ui.find_by_xpath")
    @McpDescription(
        """
        |Finds components by an XPath subset compatible with intellij-ui-test-robot — handy
        |when ui.find_by_name's free-text match is too loose and you need to filter by class
        |AND attribute together.
        |
        |Use this when: you need precise structural queries like "the ActionButton whose text
        |is 'Run'" or "the third row in this tree" — i.e. class + attribute + position
        |constraints in a single expression.
        |
        |Do NOT use this when: a plain substring search is enough (ui.find_by_name is cheaper
        |and easier), or you have coordinates (ui.find_by_coordinates).
        |
        |Supported syntax:
        |  - axes: '/' (child), '//' (descendant-or-self), '.' (self)
        |  - element names: simple class name (e.g. 'ActionButton'), 'div' or '*' (any)
        |  - attribute predicates: [@class=..], [@name=..], [@accessibleName=..], [@text=..],
        |    [@toolTipText=..]; combine with 'and'
        |  - positional predicate: [N], 1-based
        |
        |Examples:
        |  //div[@class='ActionButton' and @text='Run']
        |  //JPanel[@accessibleName='Project view']//JTree
        |  //JButton[2]
        |
        |Returns: { matches: ComponentInfo[], total: int }.
        """
    )
    suspend fun `ui_find_by_xpath`(
        @McpDescription("XPath expression in the syntax above. Wrap attribute values in single quotes.")
        xpath: String,
        @McpDescription("Cap on returned matches. Default 50.")
        limit: Int = 50,
    ): FindComponentsResponse = onEdtBlocking {
        findByXPath(xpath, limit)
    }

    @McpTool(name = "ui.get_properties")
    @McpDescription(
        """
        |Returns the complete property bag for one previously-located component, identified
        |by its id. Includes basic Swing fields (class, name, bounds, visibility), accessible
        |context (a11y name/role/description), JComponent client properties (UI hints set via
        |putClientProperty), and the IntelliJ UI-Inspector PropertyBeans when the internal
        |API is reachable.
        |
        |Use this when: you've already located a component via ui.find_by_name /
        |ui.find_by_coordinates / ui.find_by_xpath / ui.get_tree and now want everything
        |the platform knows about it.
        |
        |Do NOT use this when: you don't already have an id from a prior ui.* call in the same
        |IDE session — ids do not survive an IDE restart and dead ids return an "is no longer
        |attached" error once the panel closes. Locate the component first via ui.find_by_* or
        |ui.get_tree.
        |
        |Returns: { componentId, className, properties: [{name,value}...], warnings: string[] }.
        |Properties are key-value pairs like "bounds": "10,20 100x30", "accessibleName": "Run",
        |"clientProperty[place]": "MainToolbar", "uiInspector[ActionId]": "RunAction", etc.
        |
        |Examples:
        |  componentId="c_a3f2e1b8"                              — full property bag
        |  componentId="c_a3f2e1b8", includeClientProperties=false — slim, accessibility-only view
        """
    )
    suspend fun `ui_get_properties`(
        @McpDescription("Component id (e.g. 'c_a3f2e1b8') obtained from a prior ui.find_by_* or ui.get_tree call in the same IDE session.")
        componentId: String,
        @McpDescription("Include JComponent client properties (UI hints stored via putClientProperty).")
        includeClientProperties: Boolean = true,
        @McpDescription("Include accessibleContext (a11y name/role/description). Cheap; leave on unless responses get noisy.")
        includeAccessibleContext: Boolean = true,
    ): PropertiesResponse {
        val component = ComponentRegistry.getInstance().lookup(componentId)
            ?: throw com.intellij.mcpserver.McpExpectedError(
                "Component '$componentId' is no longer attached (panel closed or IDE restarted). " +
                    "Call ui.find_by_* or ui.get_tree again to get a fresh id.",
                kotlinx.serialization.json.JsonObject(emptyMap())
            )
        return onEdtBlocking {
            collectProperties(componentId, component, includeClientProperties, includeAccessibleContext)
        }
    }

    @McpTool(name = "ui.list_tool_windows")
    @McpDescription(
        """
        |Returns a semantic inventory of every registered tool window in the focused
        |project — NOT a Swing component tree. Each entry: id, displayName, anchor
        |(LEFT/RIGHT/BOTTOM/TOP), visibility, active/focused flag, split-mode flag,
        |type (DOCKED/FLOATING/SLIDING/WINDOWED), iconPath, content-tab count, and the
        |pluginId that contributed it (cross-referenced from our `arch.*` inventory via
        |the `com.intellij.toolWindow` extension point).
        |
        |Use this when: you need to know WHICH tool windows exist before focusing,
        |capturing, or drilling into one. Typical first call for "open the Database tool
        |window" or "what's in the Problems view".
        |
        |Do NOT use this when: you need the inner Swing tree of a specific tool window —
        |use `ui.get_tree` with `rootSelector="tool_window:<id>"`.
        |
        |Returns: { toolWindows: ToolWindowInfo[], project: string?, warnings: string[] }.
        |`iconPath` is a best-effort toString — may be null for procedural icons.
        |`providedByPluginId` is null when the window was registered programmatically.
        |
        |Examples:
        |  (no args)                  — every tool window, visible + hidden
        |  includeInvisible=false     — only currently shown
        |  nameContains="Problems"    — substring filter on id or displayName
        """
    )
    suspend fun `ui_list_tool_windows`(
        @McpDescription("Include tool windows that are currently hidden. Default true.")
        includeInvisible: Boolean = true,
        @McpDescription("Case-insensitive substring filter on id OR displayName. Null = no filter.")
        nameContains: String? = null,
    ): ToolWindowsResponse = onEdtBlocking {
        ToolWindowInspector.listToolWindows(includeInvisible, nameContains)
    }

    @McpTool(name = "ui.list_dialogs")
    @McpDescription(
        """
        |Lists currently-open `JDialog` / `java.awt.Dialog` windows across the JVM —
        |both modal and modeless. Each entry: title, ComponentRegistry id (reusable with
        |`ui.get_tree` / `ui.get_properties` / `screenshot.capture`), bounds, modality,
        |resizable flag, and the FQN of the dialog's content class (typically a
        |`DialogWrapper` subclass for IntelliJ dialogs).
        |
        |Use this when: you need to know IF a dialog is up before deciding what to do
        |next — e.g. before invoking an action, confirm no blocking modal is in the way.
        |Complements `ui.get_tree` (returns one dialog's tree) — call `ui.list_dialogs`
        |first to discover ids.
        |
        |Do NOT use this when: you want regular IDE frames (use `ui.find_by_xpath` with
        |`//IdeFrameImpl`), popups (`JBPopup` / heavyweight popup menus), or notifications
        |(live in tool windows / balloons).
        |
        |Returns: { dialogs: DialogInfo[], warnings: string[] }. `title` may be null.
        |`contentClass` resolves the `DialogWrapper` peer via
        |`DialogWrapper.findInstance(component)` when possible, else falls back to the
        |dialog's own `getClass().name`.
        |
        |Examples:
        |  (no args)                  — every showing dialog (modal + modeless)
        |  includeInvisible=true      — include dialogs that exist but aren't showing
        """
    )
    suspend fun `ui_list_dialogs`(
        @McpDescription("Include Dialogs that exist but aren't showing (isShowing==false). Default false.")
        includeInvisible: Boolean = false,
    ): DialogsResponse = onEdtBlocking {
        DialogInspector.listDialogs(includeInvisible)
    }

    @Serializable
    data class PropertiesResponse(
        val componentId: String,
        val className: String,
        val properties: List<ComponentProperty>,
        val warnings: List<String> = emptyList(),
    )

    @McpTool(name = "ui.invoke_action_on")
    @McpDescription(
        """
        |Invokes an IntelliJ AnAction with a synthetic AnActionEvent whose DataContext is
        |rooted at a previously-located Swing component. Equivalent to a real user clicking
        |that widget and triggering the named action — but addressable from an MCP agent.
        |
        |OPT-IN and SECURITY-SENSITIVE. This is a privileged WRITE operation. Actions can
        |delete files, run code, push to git, install plugins, refactor, etc. This tool is
        |off by default and, when enabled, shows a modal confirmation dialog on every call
        |(opt-out only for the rest of the session, never persisted).
        |
        |Use this when: you've already located the right Swing component via ui.find_by_*
        |or ui.get_tree and you need to invoke an action whose data context depends on that
        |component (a context-menu action on a tree node, a toolbar button bound to a
        |specific panel, an editor action on a specific editor instance).
        |
        |Do NOT use this when:
        |  - You want to invoke an action against whatever currently holds focus — use the
        |    JetBrains built-in `execute_action_by_id` MCP tool, it's lighter-weight.
        |  - You only need to read UI state (use ui.get_tree / ui.get_properties).
        |  - The action you want is destructive (delete, reset, force-push, hard refactor)
        |    AND you don't actually need the data context binding — issue the operation
        |    via the dedicated MCP tool (VCS / refactor MCPs) which carries its own
        |    confirmation UX instead of trusting our generic blocklist.
        |  - The user hasn't enabled this tool: it's off by default in
        |    Settings → Tools → IDE Introspector → "Allow UI action invocation".
        |
        |SAFETY MODEL (identical to exec.execute_kotlin_in_ide):
        |  1. Off by default. Enable in Settings → Tools → IDE Introspector → "Allow UI
        |     action invocation".
        |  2. Per-call modal confirmation by default, showing actionId, action text, owning
        |     plugin, target component class+id+bounds, and the current project. Opt-out
        |     button "Allow for this session" — in-memory only, never written to disk.
        |  3. HARD BLOCKLIST of dangerous action-id patterns (*Force*, *Delete*, *Reset*,
        |     `Vcs.RefactoringChanges`, `Reset_HEAD`, `Maven.Reimport`, plus user-extendable
        |     list in settings) triggers a SECOND confirmation dialog even when the session
        |     bypass is active and even when requireConfirmation=false. There is no way to
        |     bypass the second confirmation for blocklisted actions.
        |  4. Every call recorded to idea.log under category "ide-introspector-audit"
        |     (caller, actionId, componentId, component class, outcome, durationMs).
        |  5. Hard 10 s execution timeout via onEdtBlocking(10_000). The IDE is not allowed
        |     to hang for longer; the action either completes or the call returns
        |     ok=false, error="timeout". Note: the action's side effects may continue to
        |     run on the EDT after our timeout — this tool measures and reports, it does
        |     not actually abort an in-flight action.
        |
        |Returns: { ok:bool, actionId:string, executed:bool, presentationText:string?,
        |durationMs:long, error:string? }. `executed=true` ONLY when the action's
        |actionPerformed() was actually invoked; `executed=false` means update() reported
        |enabled=false (e.g. wrong context, dumb mode, no project) and we did not fire.
        |`ok=false` reflects either user rejection, blocklist double-prompt rejection,
        |unknown actionId, dead componentId, timeout, or thrown exception during update/
        |perform.
        |
        |Examples:
        |  actionId="Build", componentId="c_a3f2e1b8"
        |    — invokes Build against a toolbar button context
        |  actionId="QuickJavaDoc", componentId="c_91cd2204"
        |    — context action on a tree row in the Project view
        |  actionId="EditorChooseLookupItem", componentId="c_55ee0011"
        |    — completion popup action targeted at one specific editor
        """
    )
    suspend fun `ui_invoke_action_on`(
        @McpDescription("Action id registered with ActionManager (e.g. 'Build', 'QuickJavaDoc'). Non-blank.")
        actionId: String,
        @McpDescription("Component id from a prior ui.find_by_* / ui.get_tree call in the same IDE session. Format 'c_xxxxxxxx'.")
        componentId: String,
        @McpDescription("Optional cosmetic override for Presentation.text on the synthetic event — audit + dialog only. Truncated to 200 chars.")
        presentationText: String? = null,
        @McpDescription("Force a confirmation prompt even if the session bypass is active. Blocklisted ids ALWAYS double-confirm regardless of this flag.")
        requireConfirmation: Boolean = true,
    ): InvokeActionResponse {
        val settings = UiActionSettings.getInstance()
        if (!settings.enabled) {
            throw McpExpectedError(
                "ui.invoke_action_on is disabled. Enable in Settings → Tools → IDE Introspector → 'Allow UI action invocation'.",
                JsonObject(emptyMap()),
            )
        }
        if (actionId.isBlank()) {
            throw McpExpectedError("actionId must not be blank", JsonObject(emptyMap()))
        }

        // 1. Resolve action (off-EDT; getAction is thread-safe).
        val action = UiActionInvoker.findAction(actionId)
        if (action == null) {
            val error = UiActionInvoker.formatActionNotFound(actionId)
            AuditLogger.recordUiAction(actionId, componentId, null, "action-not-found", 0, error)
            return InvokeActionResponse(
                ok = false,
                actionId = actionId,
                executed = false,
                durationMs = 0,
                error = error,
            )
        }

        // 2. Resolve component (off-EDT; ComponentRegistry is synchronized).
        val component = ComponentRegistry.getInstance().lookup(componentId)
        if (component == null) {
            val error = UiActionInvoker.formatComponentDetached(componentId)
            AuditLogger.recordUiAction(actionId, componentId, null, "component-detached", 0, error)
            throw McpExpectedError(
                "Component '$componentId' is no longer attached (panel closed or IDE restarted). " +
                    "Call ui.find_by_* or ui.get_tree again to get a fresh id.",
                JsonObject(emptyMap()),
            )
        }
        val componentClass = component.javaClass.name

        // 3. Blocklist check (off-EDT).
        val isBlocklisted = UiActionBlocklist.matches(actionId, settings.blocklistedActionIds)

        // 4. Confirmation (two-stage when blocklisted).
        val project = currentCoroutineContext().projectOrNull
        val actionText = runCatching { action.templatePresentation.text }.getOrNull()
        val pluginOwner = lookupPluginOwner(action)
        val decision = UiActionConfirmationManager.confirm(
            project = project,
            actionId = actionId,
            actionText = actionText,
            pluginOwner = pluginOwner,
            component = component,
            componentId = componentId,
            requireConfirmation = requireConfirmation,
            isBlocklisted = isBlocklisted,
        )
        when (decision) {
            UiActionConfirmationManager.Decision.Rejected -> {
                AuditLogger.recordUiAction(actionId, componentId, componentClass, "user-rejected", 0, null)
                return InvokeActionResponse(
                    ok = false, actionId = actionId, executed = false,
                    presentationText = UiActionInvoker.truncatePresentationText(presentationText) ?: actionText,
                    durationMs = 0, error = "user-rejected",
                )
            }
            UiActionConfirmationManager.Decision.RejectedBlocklist -> {
                AuditLogger.recordUiAction(actionId, componentId, componentClass, "user-rejected-blocklist", 0, null)
                return InvokeActionResponse(
                    ok = false, actionId = actionId, executed = false,
                    presentationText = UiActionInvoker.truncatePresentationText(presentationText) ?: actionText,
                    durationMs = 0, error = "user-rejected-blocklist",
                )
            }
            UiActionConfirmationManager.Decision.Approved -> { /* fall through */ }
        }

        // 5. Single EDT trip — DataContext, update, perform.
        val invokeResult = try {
            onEdtBlocking(DEFAULT_EDT_TIMEOUT_MS) {
                UiActionInvoker.invoke(action, component, presentationText)
            }
        } catch (t: TimeoutException) {
            AuditLogger.recordUiAction(actionId, componentId, componentClass, "edt-timeout", DEFAULT_EDT_TIMEOUT_MS, t.message)
            return InvokeActionResponse(
                ok = false, actionId = actionId, executed = false,
                presentationText = UiActionInvoker.truncatePresentationText(presentationText) ?: actionText,
                durationMs = DEFAULT_EDT_TIMEOUT_MS, error = "edt-timeout",
            )
        }

        val outcome = when {
            !invokeResult.ok -> "error"
            invokeResult.executed -> "executed"
            else -> "not-enabled"
        }
        AuditLogger.recordUiAction(actionId, componentId, componentClass, outcome, invokeResult.durationMs, invokeResult.error)
        return InvokeActionResponse(
            ok = invokeResult.ok,
            actionId = actionId,
            executed = invokeResult.executed,
            presentationText = invokeResult.presentationText,
            durationMs = invokeResult.durationMs,
            error = invokeResult.error,
        )
    }

    /**
     * Best-effort plugin attribution for the dialog body. Falls back to `null` when the
     * platform can't tell us which plugin contributed the action class — most often the
     * case for IDE-bundled actions.
     */
    private fun lookupPluginOwner(action: com.intellij.openapi.actionSystem.AnAction): String? = runCatching {
        PluginManagerCore.getPluginByClassName(action.javaClass.name)?.idString
    }.getOrNull()

    // -------------------- core implementations --------------------

    private fun buildTree(
        maxDepth: Int,
        rootSelector: String?,
        includeInvisible: Boolean,
        includeProperties: Boolean,
        truncatePropertyValueAt: Int,
    ): UiTreeResponse {
        val registry = ComponentRegistry.getInstance()
        val roots = ComponentTreeWalker.collectRoots(rootSelector)
        val nodes = LinkedHashMap<String, ComponentInfo>()
        val warnings = mutableListOf<String>()
        var truncated = false
        val hardCap = 5_000

        for (root in roots) {
            ComponentTreeWalker.walk(root, maxDepth, includeInvisible) { c, _ ->
                if (nodes.size >= hardCap) { truncated = true; return@walk false }
                val info = ComponentSerializer.toInfo(
                    component = c,
                    registry = registry,
                    includeProperties = includeProperties,
                    truncatePropertyValueAt = truncatePropertyValueAt,
                )
                nodes[info.id] = info
                true
            }
            if (truncated) break
        }
        if (truncated) {
            warnings.add("Tree truncated at hardCap=$hardCap nodes. Narrow rootSelector or lower maxDepth.")
        }
        val rootIds = roots.map { registry.register(it) }
        return UiTreeResponse(
            nodes = nodes.values.toList(),
            rootIds = rootIds,
            truncated = truncated,
            warnings = warnings,
        )
    }

    private fun findByName(
        query: String,
        matchMode: String,
        caseSensitive: Boolean,
        searchIn: List<String>,
        limit: Int,
    ): FindComponentsResponse {
        val registry = ComponentRegistry.getInstance()
        val matches = mutableListOf<ComponentInfo>()
        val seen = HashSet<Component>()
        val match: (String) -> Boolean = when (matchMode) {
            "exact" -> { v -> if (caseSensitive) v == query else v.equals(query, ignoreCase = true) }
            "regex" -> {
                val re = Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE));
                { v -> re.containsMatchIn(v) }
            }
            else -> { v -> v.contains(query, ignoreCase = !caseSensitive) }
        }
        for (root in ComponentTreeWalker.collectRoots(null)) {
            ComponentTreeWalker.walk(root, maxDepth = 50, includeInvisible = false) { c, _ ->
                if (c in seen) return@walk true
                seen.add(c)
                if (collectFields(c, searchIn).any(match)) {
                    matches.add(ComponentSerializer.toInfo(c, registry, true, 200))
                    if (matches.size >= limit) return@walk false
                }
                true
            }
            if (matches.size >= limit) break
        }
        return FindComponentsResponse(matches, matches.size)
    }

    private fun collectFields(c: Component, searchIn: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (field in searchIn) {
            when (field) {
                "name" -> c.name?.let(out::add)
                "text" -> textOf(c)?.let(out::add)
                "accessibleName" -> (c as? Accessible)?.accessibleContext?.accessibleName?.let(out::add)
                "toolTipText" -> (c as? JComponent)?.toolTipText?.let(out::add)
            }
        }
        return out
    }

    private fun textOf(c: Component): String? = when (c) {
        is AbstractButton -> c.text
        is JLabel -> c.text
        else -> null
    }

    private fun findByCoordinates(x: Int, y: Int, space: String, returnAncestors: Boolean): FindComponentsResponse {
        val registry = ComponentRegistry.getInstance()
        val deepest = locateDeepest(x, y, space) ?: return FindComponentsResponse(emptyList(), 0)
        val chain = if (returnAncestors) listOf(deepest) + ComponentTreeWalker.ancestors(deepest) else listOf(deepest)
        val infos = chain.map { ComponentSerializer.toInfo(it, registry, true, 200) }
        return FindComponentsResponse(infos, infos.size)
    }

    private fun locateDeepest(x: Int, y: Int, space: String): Component? {
        val windows = Window.getWindows().filter { it.isShowing }
        for (w in windows) {
            val pt = if (space == "screen") {
                val rel = Point(x, y); SwingUtilities.convertPointFromScreen(rel, w); rel
            } else {
                Point(x, y)
            }
            val bounds = w.bounds
            val within = if (space == "screen") {
                x in bounds.x..(bounds.x + bounds.width) && y in bounds.y..(bounds.y + bounds.height)
            } else {
                pt.x in 0..bounds.width && pt.y in 0..bounds.height
            }
            if (!within) continue
            val deepest = SwingUtilities.getDeepestComponentAt(w, pt.x, pt.y)
            if (deepest != null) return deepest
        }
        return null
    }

    private fun findByXPath(xpath: String, limit: Int): FindComponentsResponse {
        val registry = ComponentRegistry.getInstance()
        val roots = ComponentTreeWalker.collectRoots(null)
        val nodes = LinkedHashMap<String, ComponentInfo>()
        val rootIds = mutableListOf<String>()
        for (root in roots) {
            ComponentTreeWalker.walk(root, maxDepth = 50, includeInvisible = false) { c, _ ->
                if (nodes.size >= 8_000) return@walk false
                val info = ComponentSerializer.toInfo(c, registry, includeProperties = false, truncatePropertyValueAt = 200)
                nodes[info.id] = info
                true
            }
            rootIds.add(registry.register(root))
        }
        val matches = XPathMatcher(nodes, rootIds).query(xpath, limit)
        return FindComponentsResponse(matches, matches.size)
    }

    private fun collectProperties(
        componentId: String,
        component: Component,
        includeClientProperties: Boolean,
        includeAccessibleContext: Boolean,
    ): PropertiesResponse {
        val props = mutableListOf<ComponentProperty>()
        props.add(ComponentProperty("class", component.javaClass.name))
        component.name?.let { props.add(ComponentProperty("name", it)) }
        props.add(ComponentProperty("visible", component.isVisible.toString()))
        props.add(ComponentProperty("enabled", component.isEnabled.toString()))
        val b = component.bounds
        props.add(ComponentProperty("bounds", "${b.x},${b.y} ${b.width}x${b.height}"))
        runCatching { component.locationOnScreen }.getOrNull()?.let {
            props.add(ComponentProperty("locationOnScreen", "${it.x},${it.y}"))
        }
        if (includeClientProperties && component is JComponent) {
            listOf(
                "JComponent.sizeVariant", "place", "action",
                "html.disable", "ActionToolbar.smallVariant",
            ).forEach { k ->
                component.getClientProperty(k)?.let { props.add(ComponentProperty("clientProperty[$k]", it.toString())) }
            }
        }
        if (includeAccessibleContext) {
            (component as? Accessible)?.accessibleContext?.let { ac ->
                ac.accessibleName?.let { props.add(ComponentProperty("accessibleName", it)) }
                ac.accessibleDescription?.let { props.add(ComponentProperty("accessibleDescription", it)) }
                runCatching { ac.accessibleRole?.toString() }.getOrNull()
                    ?.let { props.add(ComponentProperty("accessibleRole", it)) }
            }
        }
        val warnings = mutableListOf<String>()
        try {
            val provider = Class.forName("com.intellij.internal.inspector.UiInspectorContextProvider")
            if (provider.isInstance(component)) {
                val method = provider.getMethod("getUiInspectorContext")
                val beans = method.invoke(component) as? List<*>
                beans?.forEach { bean ->
                    if (bean == null) return@forEach
                    val n = bean.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                        ?.invoke(bean)?.toString() ?: return@forEach
                    val v = bean.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                        ?.invoke(bean)?.toString() ?: ""
                    props.add(ComponentProperty("uiInspector[$n]", v))
                }
            }
        } catch (_: ClassNotFoundException) {
            warnings.add("UiInspectorContextProvider unavailable in this build")
        } catch (t: Throwable) {
            warnings.add("UiInspector reflection failed: ${t.message}")
        }
        return PropertiesResponse(componentId, component.javaClass.name, props, warnings)
    }

    companion object {
        val DEFAULT_SEARCH_FIELDS = listOf("name", "text", "accessibleName", "toolTipText")
    }
}
