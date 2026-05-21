package com.github.xepozz.ide.introspector.core.internal

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe value holder with a TTL. The [loader] is called on demand and again
 * once the cached value is older than [ttlMs] (or when [get] is called with
 * `forceRefresh = true`).
 *
 * Both the TTL check and the clock are injectable for tests — never reach
 * for `System.currentTimeMillis()` inside business logic that has a TtlCache.
 */
internal class TtlCache<T : Any>(
    private val ttlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val loader: () -> T,
) {
    private data class Entry<T : Any>(val takenAtMs: Long, val value: T)

    private val state = AtomicReference<Entry<T>?>(null)

    fun get(forceRefresh: Boolean = false): T {
        val now = clock()
        val cached = state.get()
        if (!forceRefresh && cached != null && now - cached.takenAtMs < ttlMs) {
            return cached.value
        }
        val fresh = loader()
        state.set(Entry(now, fresh))
        return fresh
    }

    fun invalidate() { state.set(null) }

    /** For diagnostics / tests. */
    fun peek(): T? = state.get()?.value
}
