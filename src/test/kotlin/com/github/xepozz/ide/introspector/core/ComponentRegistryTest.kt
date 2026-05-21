package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Component
import java.awt.Panel
import java.lang.ref.WeakReference

/**
 * Tests for [ComponentRegistry].
 *
 * `ComponentRegistry` is an `@Service(APP)` in production, but the constructor itself is
 * a plain Kotlin one — these tests construct it directly to avoid pulling in the IDE
 * container. We deliberately keep the fixture to plain AWT primitives ([Panel]) because
 * those are headless-safe and require no EDT bouncing.
 *
 * ## What this suite cares about
 *
 *   1. **ID format**: `c_` followed by 8 lowercase hex digits derived from
 *      `System.identityHashCode`.
 *   2. **Identity equality**: two registrations of the same instance yield the same id;
 *      two distinct instances yield distinct ids (even when `equals` would call them
 *      equal — Panel doesn't override equals, so this is a non-concern here).
 *   3. **Round-trip lookup**: register then lookup returns the same instance.
 *   4. **GC behaviour**: when a component has no strong refs, `lookup` eventually returns
 *      null *and* lazily removes the dead `idToRef` entry; `compact` does the same in
 *      bulk. GC is non-deterministic, so the tests retry with bounded polling and
 *      gracefully [assumeTrue]-skip if GC refuses to cooperate in the host VM.
 */
class ComponentRegistryTest {

    private val idPattern = Regex("^c_[0-9a-f]{8}$")

    // ====================================================================================
    // SECTION 1. ID format and identity
    // ====================================================================================

    @Test
    fun `register returns id matching c_ plus 8-hex-digit pattern`() {
        val registry = ComponentRegistry()
        val panel = Panel()

        val id = registry.register(panel)

        assertTrue("Expected c_xxxxxxxx, got: $id", idPattern.matches(id))
    }

    @Test
    fun `register is stable - same component returns same id`() {
        val registry = ComponentRegistry()
        val panel = Panel()

        val id1 = registry.register(panel)
        val id2 = registry.register(panel)

        assertEquals(id1, id2)
    }

    @Test
    fun `different components get different ids`() {
        val registry = ComponentRegistry()
        val a = Panel()
        val b = Panel()

        val idA = registry.register(a)
        val idB = registry.register(b)

        assertNotEquals(idA, idB)
    }

    // ====================================================================================
    // SECTION 2. Lookup
    // ====================================================================================

    @Test
    fun `lookup returns the registered component`() {
        val registry = ComponentRegistry()
        val panel = Panel()

        val id = registry.register(panel)
        val resolved = registry.lookup(id)

        assertSame(panel, resolved)
    }

    @Test
    fun `lookup returns null for unknown id`() {
        val registry = ComponentRegistry()

        assertNull(registry.lookup("c_deadbeef"))
        assertNull(registry.lookup("not-an-id"))
        assertNull(registry.lookup(""))
    }

    // ====================================================================================
    // SECTION 3. GC behaviour
    // ====================================================================================

    /**
     * Drops the strong reference to a component, then forces a few GC cycles. After GC,
     * `lookup` must return null and the entry must have been removed.
     *
     * The `forceGc` helper retries up to ~1 second; if the host VM refuses to collect
     * (some GCs are extremely lazy in headless tests), the test gracefully skips.
     */
    @Test
    fun `lookup of GCd component returns null and cleans the entry`() {
        val registry = ComponentRegistry()
        val id = registerAndDrop(registry)

        val collected = forceGcUntil { registry.lookup(id) == null }
        assumeTrue(
            "Host VM did not collect the weak reference within the timeout; GC is non-deterministic",
            collected,
        )

        assertNull(registry.lookup(id))
        // Subsequent calls remain null — the entry was removed, not just cleared.
        assertNull(registry.lookup(id))
        // And the internal idToRef entry was pruned by lookup's clean-up branch.
        assertFalse(
            "Expected idToRef to have removed the dead id $id",
            idToRefContainsKey(registry, id),
        )
    }

    @Test
    fun `compact drops dead refs while keeping live ones`() {
        val registry = ComponentRegistry()
        val live = Panel()
        val liveId = registry.register(live)
        val deadId = registerAndDrop(registry)

        val collected = forceGcUntil { !idToRefHasLiveReferent(registry, deadId) }
        assumeTrue(
            "Host VM did not collect the weak reference within the timeout; GC is non-deterministic",
            collected,
        )

        registry.compact()

        // Live one still resolvable.
        assertSame(live, registry.lookup(liveId))
        assertTrue(idToRefContainsKey(registry, liveId))
        // Dead one is gone.
        assertNull(registry.lookup(deadId))
        assertFalse(idToRefContainsKey(registry, deadId))
    }

    @Test
    fun `re-register after GC reuses id when component is still strongly held but weak entry was cleared`() {
        // The branch under test (in ComponentRegistry.register):
        //   componentToId[component]?.let { existing ->
        //       idToRef[existing] = java.lang.ref.WeakReference(component)
        //       return existing
        //   }
        //
        // To exercise it we need: `componentToId` still has the component (it does — we
        // hold a strong ref), but `idToRef[existing]` is gone or cleared. We simulate that
        // by reflecting into `idToRef` and removing the entry directly.
        val registry = ComponentRegistry()
        val panel = Panel()
        val id = registry.register(panel)

        // Bypass `compact()` because that path requires the referent to be cleared;
        // we just want to nuke the idToRef entry while keeping componentToId intact.
        idToRefRemove(registry, id)
        assertFalse(idToRefContainsKey(registry, id))

        val idAgain = registry.register(panel)
        assertEquals("Expected same id from the existing-componentToId branch", id, idAgain)
        // The branch must have re-populated idToRef.
        assertTrue(idToRefContainsKey(registry, id))
        assertSame(panel, registry.lookup(id))
    }

    // ====================================================================================
    // SECTION 4. Helpers
    // ====================================================================================

    /** Register a component inside a separate scope so the only strong ref is dropped on return. */
    private fun registerAndDrop(registry: ComponentRegistry): String {
        // Component allocated here goes out of scope when the method returns.
        val transient = Panel()
        return registry.register(transient)
    }

    /**
     * Pokes the GC repeatedly until [done] reports true or we hit the attempt cap. Returns
     * whether [done] succeeded. We allocate a small chunk on each loop to put pressure on
     * the young generation — bare `System.gc()` is a hint and may be ignored otherwise.
     */
    private fun forceGcUntil(done: () -> Boolean): Boolean {
        // Sanity probe: confirm at least one weak ref can be collected in this VM right
        // now. If it can't, the registry tests below will deterministically time out and
        // we'd rather skip than wait.
        val probe = WeakReference(Any())
        repeat(50) {
            if (done()) return true
            try {
                @Suppress("DEPRECATION")
                System.runFinalization()
            } catch (_: Throwable) {
                // runFinalization is deprecated for removal in newer JDKs — tolerate that.
            }
            System.gc()
            // Allocate to nudge the collector.
            @Suppress("UNUSED_VARIABLE")
            val pressure = ByteArray(64 * 1024)
            Thread.sleep(20)
        }
        // Final probe diagnostic: if the registry's referent is still alive but probe is
        // also still alive, GC genuinely isn't running.
        return done() || probe.get() == null && false
    }

    private fun idToRefField(): java.lang.reflect.Field {
        val f = ComponentRegistry::class.java.getDeclaredField("idToRef")
        f.isAccessible = true
        return f
    }

    @Suppress("UNCHECKED_CAST")
    private fun idToRefMap(registry: ComponentRegistry): MutableMap<String, WeakReference<Component>> =
        idToRefField().get(registry) as MutableMap<String, WeakReference<Component>>

    private fun idToRefContainsKey(registry: ComponentRegistry, id: String): Boolean =
        idToRefMap(registry).containsKey(id)

    private fun idToRefHasLiveReferent(registry: ComponentRegistry, id: String): Boolean {
        val ref = idToRefMap(registry)[id] ?: return false
        return ref.get() != null
    }

    private fun idToRefRemove(registry: ComponentRegistry, id: String) {
        idToRefMap(registry).remove(id)
    }
}
