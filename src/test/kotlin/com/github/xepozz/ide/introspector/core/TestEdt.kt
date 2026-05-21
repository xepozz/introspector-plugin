package com.github.xepozz.ide.introspector.core

import javax.swing.SwingUtilities

/**
 * Test utilities for code that must run on the Swing Event Dispatch Thread.
 *
 * Plugin production code lives off-EDT inside `suspend` MCP handlers and bounces onto
 * the EDT via `onEdtBlocking { ... }`. Tests don't have that helper available (it pulls
 * in IDE coroutine plumbing), so we use the lower-level `SwingUtilities.invokeAndWait`.
 *
 * Usage:
 *   ```
 *   val info = onEdt { ComponentSerializer.toInfo(button, registry, ...) }
 *   ```
 */
internal fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait {
        result = runCatching { block() }
    }
    return result!!.getOrThrow()
}
