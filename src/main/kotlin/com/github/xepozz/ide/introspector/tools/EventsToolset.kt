package com.github.xepozz.ide.introspector.tools

import com.github.xepozz.ide.introspector.core.PluginInventory
import com.github.xepozz.ide.introspector.model.ListListenersResponse
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool

class EventsToolset : McpToolset {

    @McpTool(name = "events.list_listeners")
    @McpDescription(
        """
        |Lists IntelliJ message-bus listeners declared statically in plugin.xml via
        |`<applicationListeners>` and `<projectListeners>`. These are pairs of (topic class,
        |listener class) wired up by the platform on application/project initialization —
        |IntelliJ's idiomatic, declarative event-subscription mechanism.
        |
        |Use this when: you want to find "who reacts to file edits / VCS state / project open?",
        |"which listeners does plugin X register?", "what are the application-level listeners
        |that fire on startup?". Common starting point for understanding plugin event flow.
        |
        |Do NOT use this when: you want to know who subscribed *at runtime* via
        |`messageBus.connect().subscribe(...)` (out of scope — those subscriptions are
        |imperative, not enumerable), or you want the available topic classes themselves (look
        |at the topicClass field on the returned listeners, or grep platform sources for
        |`Topic<...>`).
        |
        |Returns: { listeners: ListenerInfo[], total: int } where each ListenerInfo has
        |topicClass (FQCN of the Topic), listenerClass (FQCN of the implementation),
        |area ('application'|'project'), activeInTestMode, activeInHeadlessMode, os (when
        |restricted), providedByPluginId/Name.
        |
        |Examples:
        |  topicContains="FileEditorManager"                              — every listener for FileEditorManager.Listener topics
        |  area="project", providedByPlugin="com.github.xepozz.ide.introspector" — this plugin's project listeners
        |  listenerContains="StartupActivity"                             — listeners whose impl mentions StartupActivity
        """
    )
    suspend fun events_list_listeners(
        @McpDescription("'application', 'project', or 'all'. Default 'all'.")
        area: String = "all",
        @McpDescription("Restrict to listeners contributed by this plugin id.")
        providedByPlugin: String? = null,
        @McpDescription("Case-insensitive substring filter on the topic FQCN.")
        topicContains: String? = null,
        @McpDescription("Case-insensitive substring filter on the listener implementation FQCN.")
        listenerContains: String? = null,
        @McpDescription("Cap on returned listeners. Default 500.")
        limit: Int = 500,
    ): ListListenersResponse {
        val filtered = PluginInventory.getInstance().listeners()
            .filter { area == "all" || it.area == area }
            .filter { providedByPlugin == null || it.providedByPluginId == providedByPlugin }
            .filter { topicContains == null || it.topicClass.contains(topicContains, ignoreCase = true) }
            .filter { listenerContains == null || it.listenerClass.contains(listenerContains, ignoreCase = true) }
        return ListListenersResponse(filtered.take(limit), filtered.size)
    }

    @McpTool(name = "events.find_listeners_of_topic")
    @McpDescription(
        """
        |Reverse-lookup: given a Topic FQCN, lists every static listener registered against it.
        |
        |Use this when: you have a specific topic class (e.g. `com.intellij.openapi.vfs.newvfs.BulkFileListener`)
        |and want to know what reacts to its events.
        |
        |Do NOT use this when: you have a substring (use events.list_listeners with topicContains).
        |
        |Returns: { listeners: ListenerInfo[], total: int }.
        |
        |Examples:
        |  topicClass="com.intellij.openapi.vfs.newvfs.BulkFileListener"
        |  topicClass="com.intellij.openapi.fileEditor.FileEditorManagerListener"
        """
    )
    suspend fun events_find_listeners_of_topic(
        @McpDescription("Fully-qualified Topic class name. Use the topicClass values from events.list_listeners.")
        topicClass: String,
    ): ListListenersResponse {
        val matches = PluginInventory.getInstance().listeners()
            .filter { it.topicClass == topicClass }
        return ListListenersResponse(matches, matches.size)
    }
}
