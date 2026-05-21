package com.github.xepozz.ide.introspector.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [TtlCache].
 *
 * Two goals:
 *   1. **Spec coverage**: every branch of `get` / `invalidate` / `peek` is exercised.
 *   2. **Documentation by example**: each test reads as a usage recipe — TTL caches are
 *      a "hand it a loader and a clock" affair, and the tests should make that obvious.
 *
 * ## Fixture
 *
 * We never call `System.currentTimeMillis()` directly — every test gets a [FakeClock]
 * it controls. The loader is a tiny `AtomicInteger`-backed counter so we can assert the
 * exact number of invocations.
 *
 * ## How to read these tests
 *
 * A typical test looks like:
 *
 * ```
 * val clock = FakeClock(nowMs = 1000L)
 * val calls = AtomicInteger(0)
 * val cache = TtlCache<String>(ttlMs = 100L, clock = clock::read) {
 *     calls.incrementAndGet(); "v"
 * }
 * cache.get(); cache.get()
 * assertEquals(1, calls.get())
 * ```
 *
 * If a future test starts wanting wall-clock sleeps, the TtlCache is probably leaking
 * its abstraction. Inject the clock instead.
 */
class TtlCacheTest {

    private class FakeClock(var nowMs: Long = 0L) { fun read(): Long = nowMs }

    // ====================================================================================
    // SECTION 1. TTL window
    // ====================================================================================

    @Test
    fun `loader called once within TTL window`() {
        val clock = FakeClock(nowMs = 1_000L)
        val calls = AtomicInteger(0)
        val cache = TtlCache<String>(ttlMs = 100L, clock = clock::read) {
            calls.incrementAndGet()
            "value"
        }

        cache.get()
        clock.nowMs = 1_050L
        cache.get()
        clock.nowMs = 1_099L
        cache.get()

        assertEquals(1, calls.get())
    }

    @Test
    fun `loader called again after TTL expires`() {
        val clock = FakeClock(nowMs = 0L)
        val calls = AtomicInteger(0)
        val cache = TtlCache<String>(ttlMs = 100L, clock = clock::read) {
            calls.incrementAndGet()
            "value"
        }

        cache.get()
        clock.nowMs = 100L // exactly at TTL boundary — `now - takenAt < ttl` is false, so reload
        cache.get()
        clock.nowMs = 250L
        cache.get()

        assertEquals(3, calls.get())
    }

    @Test
    fun `loader called on each get when ttl is zero`() {
        // With ttlMs = 0, the staleness check `now - takenAt < 0` is always false.
        val clock = FakeClock(nowMs = 5L)
        val calls = AtomicInteger(0)
        val cache = TtlCache<String>(ttlMs = 0L, clock = clock::read) {
            calls.incrementAndGet()
            "value"
        }

        cache.get()
        cache.get()
        cache.get()

        assertEquals(3, calls.get())
    }

    // ====================================================================================
    // SECTION 2. forceRefresh
    // ====================================================================================

    @Test
    fun `forceRefresh bypasses the cache`() {
        val clock = FakeClock(nowMs = 1_000L)
        val calls = AtomicInteger(0)
        val cache = TtlCache<String>(ttlMs = 60_000L, clock = clock::read) {
            calls.incrementAndGet()
            "value"
        }

        cache.get()                 // cold load
        cache.get()                 // cached
        cache.get(forceRefresh = true)  // forced reload despite being inside TTL

        assertEquals(2, calls.get())
    }

    // ====================================================================================
    // SECTION 3. invalidate
    // ====================================================================================

    @Test
    fun `invalidate forces next get to reload`() {
        val clock = FakeClock(nowMs = 1_000L)
        val calls = AtomicInteger(0)
        val cache = TtlCache<String>(ttlMs = 60_000L, clock = clock::read) {
            calls.incrementAndGet()
            "value"
        }

        cache.get()
        cache.invalidate()
        cache.get()

        assertEquals(2, calls.get())
    }

    // ====================================================================================
    // SECTION 4. peek
    // ====================================================================================

    @Test
    fun `peek returns null before first get`() {
        val cache = TtlCache<String>(ttlMs = 100L, clock = { 0L }) { "v" }
        assertNull(cache.peek())
    }

    @Test
    fun `peek returns the cached value after get`() {
        val cache = TtlCache<String>(ttlMs = 100L, clock = { 0L }) { "hello" }
        cache.get()
        assertEquals("hello", cache.peek())
    }

    @Test
    fun `peek does not trigger a load`() {
        val calls = AtomicInteger(0)
        val cache = TtlCache<String>(ttlMs = 100L, clock = { 0L }) {
            calls.incrementAndGet()
            "v"
        }

        cache.peek()
        cache.peek()
        cache.peek()

        assertEquals(0, calls.get())
    }

    @Test
    fun `peek returns null after invalidate`() {
        val cache = TtlCache<String>(ttlMs = 100L, clock = { 0L }) { "v" }
        cache.get()
        assertNotNull(cache.peek())
        cache.invalidate()
        assertNull(cache.peek())
    }

    // ====================================================================================
    // SECTION 5. Return value
    // ====================================================================================

    @Test
    fun `get returns the loader's return value`() {
        val expected = "result-${System.nanoTime()}"
        val cache = TtlCache<String>(ttlMs = 100L, clock = { 0L }) { expected }
        assertEquals(expected, cache.get())
    }

    @Test
    fun `get returns the same cached instance on repeated reads`() {
        // The loader returns a fresh object each time it runs; within the TTL window
        // every reader must see the exact instance that the cold load produced.
        val cache = TtlCache<Any>(ttlMs = 60_000L, clock = { 1_000L }) { Any() }
        val first = cache.get()
        val second = cache.get()
        assertSame(first, second)
    }

    // ====================================================================================
    // SECTION 6. Concurrency
    // ====================================================================================

    @Test
    fun `concurrent gets within TTL share the cached value`() {
        // TtlCache uses a plain set on the AtomicReference, not a CAS — meaning multiple
        // threads racing the cold path may all invoke the loader. That's documented as
        // expected behaviour: the cost is small (a few duplicate loads on startup) and
        // avoiding it would require a lock. We assert what callers actually care about:
        // after the dust settles, peek() reflects a value that all gets agreed on.
        val calls = AtomicInteger(0)
        val cache = TtlCache<Int>(ttlMs = 60_000L, clock = { 1_000L }) {
            calls.incrementAndGet()
            42
        }

        val threadCount = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val results = java.util.Collections.synchronizedList(mutableListOf<Int>())

        repeat(threadCount) {
            executor.submit {
                ready.countDown()
                go.await()
                results.add(cache.get())
                done.countDown()
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        done.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // Loader was called at least once — and possibly more on the cold path. Either is fine.
        assertTrue("loader must run at least once, got ${calls.get()}", calls.get() >= 1)
        // Every reader saw the same value.
        assertEquals(threadCount, results.size)
        assertTrue("all readers must agree on the value, got $results", results.all { it == 42 })
        // After the race, the cache exposes the agreed-upon value.
        assertEquals(42, cache.peek())
    }
}
