package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ActionInventory
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform test pinning the (deliberately limited) semantics of `arch.list_actions`'s
 * `includeInternal` arg and the [com.github.xepozz.ide.introspector.model.ActionInfo.isInternal]
 * field.
 *
 * Why this test exists: an earlier fix swapped `AnAction.isInternal()` (removed in the 252
 * platform) for an `@ApiStatus.Internal` annotation probe, claiming equivalence. The two
 * sets barely overlap — `@ApiStatus.Internal` is API-stability metadata, while
 * `<action internal="true"/>` is the runtime-visibility gate the IDE uses for the
 * Internal Actions menu. The current implementation drops the bogus detection entirely
 * and documents the arg as a no-op — see [ActionInventory.resolveAction] kdoc for the
 * long explanation. This test guards that contract.
 *
 * Three properties are exercised:
 *
 *  1. **`includeInternal` is a true no-op.** Two calls with the same query and limit but
 *     different `includeInternal` values must return the same id set. Regression target:
 *     the old `@ApiStatus.Internal` probe would falsely drop entries from the
 *     `includeInternal=false` response.
 *  2. **`ActionInfo.isInternal` is always false.** We cannot honestly determine the
 *     parser-time `internal="true"` bit post-registration, so the field must be reported
 *     as `false` rather than guessed from the wrong source.
 *  3. **The known internal action `UiInspector` is reachable when the IDE is in internal
 *     mode** (which `BasePlatformTestCase` enables by default — `Application.isInternal()`
 *     returns true). This is the canonical `<action internal="true"/>` example in the
 *     platform; if our walk fails to surface it in internal-mode IDEs we have a real
 *     regression.
 *
 * If a future platform release exposes a stable internal-flag accessor and we wire it
 * through, properties (1) and (2) will need updating — which is the right time to refresh
 * the kdoc on [ActionInventory.resolveAction] as well.
 */
class ActionInventoryInternalFlagPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        ActionInventory.getInstance().invalidateAll()
    }

    fun testIncludeInternalIsANoOpForOrdinaryActions() {
        // For a non-internal action like 'EditorCopy', both calls must return identical
        // payloads (modulo cache key). If `includeInternal=false` were silently filtering
        // on some bogus heuristic (e.g. @ApiStatus.Internal on the class), entries could
        // get dropped from one response and not the other — the test would catch it.
        val withoutInternal = ActionInventory.getInstance().listActions(
            query = "EditorCopy",
            includeInternal = false,
            limit = 50,
        )
        val withInternal = ActionInventory.getInstance().listActions(
            query = "EditorCopy",
            includeInternal = true,
            limit = 50,
        )

        assertEquals(
            "includeInternal must not change the set of returned ids — it is documented " +
                "as a no-op. Got withoutInternal=${withoutInternal.actions.map { it.id }}, " +
                "withInternal=${withInternal.actions.map { it.id }}",
            withoutInternal.actions.map { it.id }.toSet(),
            withInternal.actions.map { it.id }.toSet(),
        )
    }

    fun testActionInfoIsInternalAlwaysFalse() {
        // Probe a generic-enough query so the response is non-empty across IDE flavours.
        // We don't care about specific ids — only that the `isInternal` field is never
        // claimed `true` based on the deleted heuristic.
        val response = ActionInventory.getInstance().listActions(
            query = "Editor",
            includeInternal = true,
            limit = 50,
        )
        assertFalse(
            "Response should be non-empty for a generic 'Editor' query, otherwise the " +
                "isInternal assertion below is vacuous",
            response.actions.isEmpty(),
        )
        for (action in response.actions) {
            assertFalse(
                "ActionInfo.isInternal must always be false until the platform exposes a " +
                    "stable post-registration accessor for <action internal=\"true\"/> — see " +
                    "ActionInventory.resolveAction kdoc. Offending id: ${action.id}",
                action.isInternal,
            )
        }
    }

    fun testKnownInternalActionReachableWhenIDEIsInternalMode() {
        val app = ApplicationManager.getApplication()
        if (!app.isInternal) {
            // Outside internal mode the platform itself never registers <action internal="true">
            // entries — our walk literally cannot see them and the assertion below would fail
            // for reasons orthogonal to this fix. Skip rather than fail.
            return
        }
        // Sanity check: the action is registered in this IDE / build. If it isn't (e.g.
        // the bundled `intellij.platform.ide.internal.inspector` module isn't shipped in
        // this sandbox), skip — we don't want a flaky env-dependent failure here.
        val rawAction = ActionManager.getInstance()?.getAction(INTERNAL_ACTION_ID)
        if (rawAction == null) {
            return
        }

        val responseDefault = ActionInventory.getInstance().listActions(
            query = INTERNAL_ACTION_ID,
            includeInternal = false,
            limit = 50,
        )
        val responseOptIn = ActionInventory.getInstance().listActions(
            query = INTERNAL_ACTION_ID,
            includeInternal = true,
            limit = 50,
        )

        val foundDefault = responseDefault.actions.firstOrNull { it.id == INTERNAL_ACTION_ID }
        val foundOptIn = responseOptIn.actions.firstOrNull { it.id == INTERNAL_ACTION_ID }

        assertNotNull(
            "Internal-mode IDE: '$INTERNAL_ACTION_ID' must be visible in the default " +
                "response (we just confirmed it is registered). Got ids=" +
                responseDefault.actions.map { it.id },
            foundDefault,
        )
        assertNotNull(
            "Internal-mode IDE: '$INTERNAL_ACTION_ID' must also be visible with " +
                "includeInternal=true. Got ids=${responseOptIn.actions.map { it.id }}",
            foundOptIn,
        )
        assertEquals(
            "ActionInfo.isInternal must be false even for a known internal action — see " +
                "ActionInventory.resolveAction kdoc",
            false,
            foundDefault!!.isInternal,
        )
    }

    companion object {
        /**
         * `UiInspector` is declared in `intellij.platform.ide.internal.inspector` as
         * `<action id="UiInspector" internal="true" class="com.intellij.internal.inspector.UiInspectorAction"/>`,
         * gated by internal mode. Used here as the canonical internal-action probe. (The
         * Internal Actions menu surfaces it under the same id; the menu PATH may include
         * "Internal/UI/Show UI Inspector" but the action id itself is `UiInspector`.)
         */
        private const val INTERNAL_ACTION_ID = "UiInspector"
    }
}
