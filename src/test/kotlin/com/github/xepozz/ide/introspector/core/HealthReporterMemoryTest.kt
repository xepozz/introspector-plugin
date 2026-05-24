package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage

/**
 * Unit tests for [HealthReporter.memory] and [HealthReporter.formatUptime].
 *
 * These are pure-JVM tests — no IntelliJ Platform classes are touched, so they run
 * under the standard JUnit runner without a sandbox IDE. Threading invariants
 * (`MXBean` thread safety, no EDT) are inherited from the JMX spec and don't need
 * a test of their own.
 */
class HealthReporterMemoryTest {

    // ====================================================================================
    // memory() — happy path against the live JVM
    // ====================================================================================

    @Test
    fun `memory returns a populated heap block`() {
        val snapshot = HealthReporter.memory()

        assertTrue(
            "heap.used must be > 0 in a running JVM, got ${snapshot.heap.used}",
            snapshot.heap.used > 0L,
        )
        // The unit-test JVM always has -Xmx set, so max should be a positive bound.
        assertTrue(
            "heap.max must be > heap.used, got max=${snapshot.heap.max} used=${snapshot.heap.used}",
            snapshot.heap.max > snapshot.heap.used,
        )
        assertTrue(
            "heap.committed must be >= heap.used, got committed=${snapshot.heap.committed} used=${snapshot.heap.used}",
            snapshot.heap.committed >= snapshot.heap.used,
        )
        // freeBytes derivation: committed - used, never negative.
        val expectedFree = (snapshot.heap.committed - snapshot.heap.used).coerceAtLeast(0L)
        assertEquals("heap.freeBytes must equal committed - used", expectedFree, snapshot.heap.freeBytes)
    }

    @Test
    fun `memory populates nonHeap and counters`() {
        val snapshot = HealthReporter.memory()

        assertTrue("nonHeap.used must be > 0", snapshot.nonHeap.used > 0L)
        assertTrue("threadCount must be > 0", snapshot.threadCount > 0)
        assertTrue("classCount must be > 0", snapshot.classCount > 0)
        assertTrue("uptime must be >= 0", snapshot.uptime >= 0L)
    }

    @Test
    fun `memory gcs are non-empty with non-blank names`() {
        val snapshot = HealthReporter.memory()

        assertFalse("Every JVM has at least one GC bean exposed", snapshot.gcs.isEmpty())
        for (gc in snapshot.gcs) {
            assertTrue("GC name must be non-blank, got '${gc.name}'", gc.name.isNotBlank())
            assertTrue("collectionCount must be >= 0, got ${gc.collectionCount}", gc.collectionCount >= 0L)
            assertTrue("collectionTimeMs must be >= 0, got ${gc.collectionTimeMs}", gc.collectionTimeMs >= 0L)
        }
    }

    @Test
    fun `memory uptime matches the platform RuntimeMXBean within tolerance`() {
        val before = ManagementFactory.getRuntimeMXBean().uptime
        val snapshot = HealthReporter.memory()
        val after = ManagementFactory.getRuntimeMXBean().uptime
        assertTrue(
            "snapshot.uptime ${snapshot.uptime} must be between $before and $after (inclusive of small slack)",
            snapshot.uptime in (before - 5L)..(after + 5L),
        )
    }

    @Test
    fun `memory uptimeFormatted is consistent with uptime`() {
        val snapshot = HealthReporter.memory()
        // The formatter is exhaustively tested below; here we just guard against
        // accidental mis-wiring of the two fields.
        assertEquals(
            "uptimeFormatted must equal formatUptime(uptime)",
            HealthReporter.formatUptime(snapshot.uptime),
            snapshot.uptimeFormatted,
        )
    }

    // ====================================================================================
    // memory(gcBeforeRead = true) — does not throw even when GC is a no-op
    // ====================================================================================

    @Test
    fun `memory with gcBeforeRead true succeeds`() {
        // We cannot assert that the heap actually shrinks — `System.gc()` is a hint
        // and the JVM is free to ignore it (`-XX:+DisableExplicitGC`). We only need to
        // demonstrate the call itself doesn't blow up.
        val snapshot = HealthReporter.memory(gcBeforeRead = true)
        assertNotNull("snapshot must be returned even when GC is requested", snapshot)
        assertTrue("heap still populated after explicit GC hint", snapshot.heap.used > 0L)
    }

    // ====================================================================================
    // Metaspace pool fallback
    // ====================================================================================

    @Test
    fun `memory returns -1 max metaspace fallback when no Metaspace pool present`() {
        // Synthetic empty pool list — simulates non-HotSpot JVMs (J9, Zing) where the
        // pool named "Metaspace" is absent.
        val snapshot = HealthReporter.memory(
            memoryMXBean = ManagementFactory.getMemoryMXBean(),
            poolMXBeans = emptyList(),
        )
        assertEquals("metaspace.used falls back to 0", 0L, snapshot.metaspace.used)
        assertEquals("metaspace.max falls back to -1 (unbounded)", -1L, snapshot.metaspace.max)
        assertEquals("metaspace.committed falls back to 0", 0L, snapshot.metaspace.committed)
        assertEquals("metaspace.freeBytes falls back to 0", 0L, snapshot.metaspace.freeBytes)
    }

    @Test
    fun `memory picks the Metaspace pool when present`() {
        val fakePool = FakeMetaspacePool(
            used = 12_345L,
            committed = 24_690L,
            max = 100_000L,
        )
        val snapshot = HealthReporter.memory(
            memoryMXBean = ManagementFactory.getMemoryMXBean(),
            poolMXBeans = listOf(fakePool),
        )
        assertEquals(12_345L, snapshot.metaspace.used)
        assertEquals(24_690L, snapshot.metaspace.committed)
        assertEquals(100_000L, snapshot.metaspace.max)
        // freeBytes = committed - used.
        assertEquals(24_690L - 12_345L, snapshot.metaspace.freeBytes)
    }

    @Test
    fun `memory accepts negative max from JMX as 'unbounded'`() {
        val unboundedPool = FakeMetaspacePool(used = 1L, committed = 2L, max = -1L)
        val snapshot = HealthReporter.memory(poolMXBeans = listOf(unboundedPool))
        assertEquals("-1 max passes through unchanged (JMX 'unbounded' convention)", -1L, snapshot.metaspace.max)
    }

    @Test
    fun `memory honours custom MemoryMXBean injection`() {
        val fake = FakeMemoryMXBean(
            heap = MemoryUsage(0L, 100L, 200L, 1_000L),
            nonHeap = MemoryUsage(0L, 50L, 75L, 500L),
        )
        val snapshot = HealthReporter.memory(memoryMXBean = fake, poolMXBeans = emptyList())
        assertEquals(100L, snapshot.heap.used)
        assertEquals(200L, snapshot.heap.committed)
        assertEquals(1_000L, snapshot.heap.max)
        assertEquals(200L - 100L, snapshot.heap.freeBytes)
        assertEquals(50L, snapshot.nonHeap.used)
    }

    // ====================================================================================
    // formatUptime — the spec calls out "Xh Ym Zs" / "Ym Zs" / "Zs"
    // ====================================================================================

    @Test
    fun `formatUptime renders seconds only when under a minute`() {
        assertEquals("0s", HealthReporter.formatUptime(0L))
        assertEquals("0s", HealthReporter.formatUptime(999L))                 // sub-second rounds down
        assertEquals("1s", HealthReporter.formatUptime(1_000L))
        assertEquals("59s", HealthReporter.formatUptime(59_999L))
    }

    @Test
    fun `formatUptime renders minutes and seconds with zero-padded seconds`() {
        assertEquals("1m 00s", HealthReporter.formatUptime(60_000L))
        assertEquals("1m 05s", HealthReporter.formatUptime(65_000L))
        assertEquals("59m 59s", HealthReporter.formatUptime(59 * 60_000L + 59_000L))
    }

    @Test
    fun `formatUptime renders hours minutes seconds with zero padding`() {
        assertEquals("1h 12m 03s", HealthReporter.formatUptime(3_600_000L + 12 * 60_000L + 3_000L))
        assertEquals("1h 00m 00s", HealthReporter.formatUptime(3_600_000L))
        assertEquals("23h 59m 59s", HealthReporter.formatUptime(24L * 3_600_000L - 1L))
    }

    @Test
    fun `formatUptime renders days for ultra-long uptimes`() {
        assertEquals("1d 00h 00m 00s", HealthReporter.formatUptime(24L * 3_600_000L))
        assertEquals(
            "3d 04h 05m 06s",
            HealthReporter.formatUptime(3L * 86_400_000L + 4L * 3_600_000L + 5L * 60_000L + 6_000L),
        )
    }

    @Test
    fun `formatUptime clamps negative input to 0s`() {
        assertEquals("0s", HealthReporter.formatUptime(-1L))
        assertEquals("0s", HealthReporter.formatUptime(Long.MIN_VALUE))
    }

    // ====================================================================================
    // Fixtures
    // ====================================================================================

    /**
     * Minimal stub MemoryPoolMXBean — only the bits HealthReporter reads (name + usage)
     * are non-trivial; everything else throws if accidentally called so a test wiring
     * bug surfaces loudly rather than silently using a default.
     */
    private class FakeMetaspacePool(
        used: Long,
        committed: Long,
        max: Long,
    ) : MemoryPoolMXBean {
        private val usageObj = MemoryUsage(0L, used, committed, max)
        override fun getName(): String = "Metaspace"
        override fun getType(): MemoryType = MemoryType.NON_HEAP
        override fun getUsage(): MemoryUsage = usageObj
        override fun getPeakUsage(): MemoryUsage = usageObj
        override fun resetPeakUsage() = error("not used in test")
        override fun isValid(): Boolean = true
        override fun getMemoryManagerNames(): Array<String> = arrayOf("Metaspace Manager")
        override fun getUsageThreshold(): Long = 0L
        override fun setUsageThreshold(threshold: Long) = error("not used in test")
        override fun isUsageThresholdExceeded(): Boolean = false
        override fun getUsageThresholdCount(): Long = 0L
        override fun isUsageThresholdSupported(): Boolean = false
        override fun getCollectionUsage(): MemoryUsage? = null
        override fun getCollectionUsageThreshold(): Long = 0L
        override fun setCollectionUsageThreshold(threhsold: Long) = error("not used in test")
        override fun isCollectionUsageThresholdExceeded(): Boolean = false
        override fun getCollectionUsageThresholdCount(): Long = 0L
        override fun isCollectionUsageThresholdSupported(): Boolean = false
        override fun getObjectName(): javax.management.ObjectName =
            javax.management.ObjectName("java.lang:type=MemoryPool,name=Metaspace")
    }

    /** Trivial MemoryMXBean stub — only `getHeapMemoryUsage` / `getNonHeapMemoryUsage` matter. */
    private class FakeMemoryMXBean(
        private val heap: MemoryUsage,
        private val nonHeap: MemoryUsage,
    ) : MemoryMXBean {
        override fun getObjectPendingFinalizationCount(): Int = 0
        override fun getHeapMemoryUsage(): MemoryUsage = heap
        override fun getNonHeapMemoryUsage(): MemoryUsage = nonHeap
        override fun isVerbose(): Boolean = false
        override fun setVerbose(value: Boolean) = error("not used in test")
        override fun gc() = error("not used in test")
        override fun getObjectName(): javax.management.ObjectName =
            javax.management.ObjectName("java.lang:type=Memory")
    }
}
