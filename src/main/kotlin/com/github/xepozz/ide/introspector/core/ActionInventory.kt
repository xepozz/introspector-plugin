package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.core.internal.TtlCache
import com.github.xepozz.ide.introspector.model.ActionInfo
import com.github.xepozz.ide.introspector.model.ListActionsResponse
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil

/**
 * Application-level catalog of every action registered against [ActionManager], driving
 * `arch.list_actions` (and reusable from the tool window in the future).
 *
 * ## Design
 *
 * The full action set in a vanilla IDEA install is 3000+ entries. The naive path —
 * `ActionManager.getActionIdList("")` then `getAction(id)` for every id — triggers
 * class loading for every action and saturates the action manager. We avoid that with:
 *
 *  1. **Prefix fast-path.** When `query` is alphanumeric (no wildcards, no whitespace),
 *     [ActionManagerEx.getActionIdList] does the prefix match at the registry level
 *     with no [AnAction] instantiation.
 *  2. **Lazy stub lookup.** After registry filtering produces the candidate id set, we
 *     try [ActionManager.getActionOrStub] (reflection — internal API) and only fall
 *     back to [ActionManager.getAction] when the stub returns null. Stubs carry the
 *     template presentation without loading the action class.
 *  3. **Reverse plugin map.** Built once per cache entry by iterating
 *     [PluginManagerCore.plugins] and calling [ActionManagerEx.getPluginActions]. Lookup
 *     during the walk is then O(1).
 *  4. **Per-key TTL cache.** Each (query, providedByPluginId, includeInternal, limit) tuple
 *     gets its own [TtlCache] entry — 60 s window (actions change only on plugin
 *     install/disable, so this is generous and still bounded).
 *  5. **10 s hard timeout.** Implemented as a per-walk wall-clock deadline. If the
 *     budget is exhausted mid-walk we return the accumulated list with
 *     `truncated=true` and `total=accumulated.size`.
 */
@Service(Service.Level.APP)
class ActionInventory {

    data class CacheKey(
        val query: String?,
        val providedByPluginId: String?,
        val includeInternal: Boolean,
        val limit: Int,
    )

    /**
     * Per-args TTL window. A small map keeps the cumulative footprint bounded — every
     * (query, pluginId, includeInternal, limit) tuple owns one [TtlCache] entry, but
     * realistic agent workloads keep the unique-key count in the dozens.
     */
    private val perKeyCache = java.util.concurrent.ConcurrentHashMap<CacheKey, TtlCache<ListActionsResponse>>()

    fun listActions(
        query: String? = null,
        providedByPluginId: String? = null,
        includeInternal: Boolean = false,
        limit: Int = 200,
    ): ListActionsResponse {
        val clampedLimit = limit.coerceIn(1, MAX_LIMIT)
        // NOTE: `includeInternal` is currently a NO-OP — see kdoc on [isInternalAction] and the
        // ActionInfo.isInternal field docs. It still participates in the cache key so a
        // future implementation that wires the flag through doesn't accidentally serve a
        // stale unfiltered list to callers that opted in.
        val key = CacheKey(query, providedByPluginId, includeInternal, clampedLimit)
        val cache = perKeyCache.computeIfAbsent(key) { k ->
            TtlCache(ttlMs = CACHE_TTL_MS) { collect(k) }
        }
        return cache.get()
    }

    /** Forces every cached entry to reload on next access — used by tests. */
    fun invalidateAll() { perKeyCache.clear() }

    private fun collect(key: CacheKey): ListActionsResponse {
        val deadlineMs = System.currentTimeMillis() + WALK_TIMEOUT_MS
        val am = ActionManager.getInstance() ?: return ListActionsResponse(emptyList(), 0, false)
        val amEx = runCatching { ActionManagerEx.getInstanceEx() }.getOrNull()
        val reversePluginMap = buildReversePluginMap(amEx)

        // ----- Phase 1: pick the candidate id list (smallest set we can manage).
        val candidates: List<String> = when {
            // Plugin filter has the smallest possible candidate set.
            key.providedByPluginId != null -> {
                val pid = PluginId.getId(key.providedByPluginId)
                runCatching { amEx?.getPluginActions(pid)?.toList() ?: emptyList() }
                    .getOrElse { emptyList() }
            }
            // Prefix fast-path: alphanumeric query → registry-level lookup.
            isPrefixQuery(key.query) -> {
                runCatching { amEx?.getActionIdList(key.query!!)?.toList() ?: emptyList() }
                    .getOrElse { emptyList() }
            }
            // Full enumeration — registry-level, no AnAction instantiation.
            else -> {
                runCatching { amEx?.getActionIdList("")?.toList() ?: emptyList() }
                    .getOrElse { emptyList() }
            }
        }

        // ----- Phase 2: stream the candidate list, stop at limit OR deadline.
        val out = mutableListOf<ActionInfo>()
        var truncated = false
        var matchedCount = 0
        val queryLower = key.query?.lowercase()
        // Whether substring filtering still needs to happen after Phase 1's registry pass.
        // The plugin-only path took every action regardless of query — we must still apply
        // the substring filter against the user's query for those entries.
        val needSubstring = queryLower != null &&
            (key.providedByPluginId != null || !isPrefixQuery(key.query))

        for (id in candidates) {
            if (System.currentTimeMillis() > deadlineMs) {
                truncated = true
                break
            }

            // Substring filtering needs the display text → resolve the action (cheap stub when
            // possible). For the cheaper no-filter path we still resolve so toActionInfo can
            // populate text/description, but `includeInternal` is intentionally NOT consulted
            // because the platform itself decides registration based on internal-mode (see
            // kdoc on [isInternalAction]) — we have no post-registration way to distinguish.
            val anAction: AnAction? = resolveAction(am, id)

            // Apply substring filter against id + display text after we have the action.
            if (needSubstring) {
                val text = anAction?.templatePresentation?.text
                val idMatch = id.contains(queryLower!!, ignoreCase = true)
                val textMatch = text != null && text.contains(queryLower, ignoreCase = true)
                if (!idMatch && !textMatch) continue
            }

            matchedCount++
            if (out.size < key.limit) {
                out.add(toActionInfo(id, anAction, reversePluginMap, isInternal = false))
            } else {
                truncated = true
                // Keep counting matches so total is accurate. But to honour the 10 s budget
                // we still respect the deadline above.
            }
        }

        val total = maxOf(matchedCount, out.size)
        return ListActionsResponse(actions = out, total = total, truncated = truncated)
    }

    /**
     * `<action internal="true"/>` is a **registration-time** flag, NOT a queryable property of
     * a registered [AnAction]. The platform's own [com.intellij.openapi.actionSystem.impl.ActionManagerImpl]
     * reads `ActionElement.ActionDescriptorAction.isInternal` while parsing plugin.xml and:
     *
     *  - If the IDE is NOT in internal mode ([com.intellij.openapi.application.Application.isInternal]
     *    returns `false`, i.e. `-Didea.is.internal=true` is absent), the action is appended to
     *    the private `notRegisteredInternalActionIds` list and `processActionElement` returns
     *    `null` — the action is **never registered**. It does not appear in
     *    [com.intellij.openapi.actionSystem.ex.ActionManagerEx.getActionIdList], so our
     *    `includeInternal=true` cannot resurrect it.
     *  - If the IDE IS in internal mode, the action is registered identically to a regular
     *    action — the `isInternal` bit is consumed by the parser and not stored on the
     *    resulting [AnAction] instance. There is no public, supported API exposing this.
     *
     * The previous implementation checked `@ApiStatus.Internal` on the action class. That
     * annotation is an API-stability marker — orthogonal to runtime visibility — and the
     * two sets barely overlap: most platform internal-mode actions
     * (e.g. `Internal.UI.ShowUiInspector`, `DumpInspectionDescriptions`) are public Java
     * classes with no `@ApiStatus.Internal`, while many `@ApiStatus.Internal`-marked
     * classes are perfectly user-facing.
     *
     * Until a stable lookup surfaces in a future platform release, the `includeInternal`
     * arg on `arch.list_actions` is a **no-op** and [ActionInfo.isInternal] is always
     * `false`. Callers running an internal-mode IDE will see the actions in the response
     * either way; callers running a normal IDE never could.
     */
    private fun resolveAction(am: ActionManager, id: String): AnAction? {
        // Try `getActionOrStub` reflectively — it's `@ApiStatus.Internal` and not part of
        // the public ActionManager surface. Stubs are far cheaper than full class loads.
        val stub = runCatching {
            val method = GET_ACTION_OR_STUB_METHOD.value
            method?.invoke(am, id) as? AnAction
        }.getOrNull()
        if (stub != null) return stub
        return runCatching { am.getAction(id) }.getOrNull()
    }

    private fun toActionInfo(
        id: String,
        action: AnAction?,
        reversePluginMap: Map<String, Pair<String, String?>>,
        isInternal: Boolean,
    ): ActionInfo {
        val text = action?.templatePresentation?.text
        val description = action?.templatePresentation?.description
        val iconPath = runCatching { action?.templatePresentation?.icon?.toString() }.getOrNull()
        val shortcuts = formatShortcuts(id)
        val owner = reversePluginMap[id]
        return ActionInfo(
            id = id,
            text = text,
            description = description,
            providedByPluginId = owner?.first,
            providedByPluginName = owner?.second,
            keyboardShortcuts = shortcuts,
            category = null, // see ActionInfo kdoc — left null by design
            iconPath = iconPath,
            isInternal = isInternal,
        )
    }

    private fun formatShortcuts(id: String): String? = runCatching {
        val keymap = KeymapManager.getInstance()?.activeKeymap ?: return@runCatching null
        val shortcuts = keymap.getShortcuts(id)
        if (shortcuts.isEmpty()) null
        else shortcuts.joinToString(", ") { KeymapUtil.getShortcutText(it) }
    }.getOrNull()

    private fun buildReversePluginMap(amEx: ActionManagerEx?): Map<String, Pair<String, String?>> {
        if (amEx == null) return emptyMap()
        val map = HashMap<String, Pair<String, String?>>(2048)
        @Suppress("UnstableApiUsage")
        for (descriptor in PluginManagerCore.plugins) {
            val pid = descriptor.pluginId
            val ids = runCatching { amEx.getPluginActions(pid) }.getOrElse { emptyArray() }
            val pluginIdString = pid.idString
            val pluginName = descriptor.name
            for (id in ids) {
                // First writer wins — the IDE's own ordering is fine.
                map.putIfAbsent(id, pluginIdString to pluginName)
            }
        }
        return map
    }

    companion object {
        const val CACHE_TTL_MS = 60_000L
        const val WALK_TIMEOUT_MS = 10_000L
        const val MAX_LIMIT = 2000

        /** Matches the pattern documented in the plan: alphanumeric + dot + underscore. */
        private val PREFIX_QUERY_REGEX = Regex("^[A-Za-z0-9_.]+$")

        /**
         * One-shot reflective lookup for [com.intellij.openapi.actionSystem.impl.ActionManagerImpl.getActionOrStub].
         * The method is `@ApiStatus.Internal` so we can't bind to it at compile time, but the
         * concrete class is a singleton — its `Class` object is stable across calls. Hoisting
         * the [java.lang.Class.getMethods] scan out of the per-id loop avoids ~3000 fresh
         * `Method[]` allocations (one per call to `getMethods()`) on an unfiltered walk.
         */
        private val GET_ACTION_OR_STUB_METHOD: Lazy<java.lang.reflect.Method?> = lazy {
            runCatching {
                ActionManager.getInstance()?.javaClass?.methods?.firstOrNull {
                    it.name == "getActionOrStub" && it.parameterCount == 1
                }
            }.getOrNull()
        }

        fun getInstance(): ActionInventory = service()

        /**
         * A "prefix query" is safe to pass straight to [ActionManagerEx.getActionIdList],
         * which does a case-sensitive prefix match on action ids at the registry level
         * with no [AnAction] instantiation.
         *
         * Returns false for null, blank, or any string containing whitespace / wildcards
         * — those must go through the full enumeration path so the substring matcher gets
         * a chance against both the id and the display text.
         */
        fun isPrefixQuery(query: String?): Boolean {
            if (query.isNullOrBlank()) return false
            return PREFIX_QUERY_REGEX.matches(query)
        }
    }
}
