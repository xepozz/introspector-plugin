package com.github.xepozz.introspectorplugin.util

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.SwingUtilities

/**
 * Runs [block] on the EDT and waits for the result up to [timeoutMs] ms.
 * The MCP tool handlers always run on a background HTTP thread, so callers must wrap
 * any Swing access in this helper.
 */
fun <T> onEdtBlocking(timeoutMs: Long = 5_000, block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return block()
    }
    val app = ApplicationManager.getApplication()
    var result: Result<T>? = null
    val latch = java.util.concurrent.CountDownLatch(1)
    app.invokeLater {
        result = runCatching(block)
        latch.countDown()
    }
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw TimeoutException("EDT operation timed out after ${timeoutMs}ms")
    }
    return result!!.getOrThrow()
}
