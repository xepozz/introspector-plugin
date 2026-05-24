package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ListenerInspector.toListenerInfo].
 *
 * `ListenerDescriptor` is `@ApiStatus.Internal` so we feed synthetic doubles with the same
 * field shapes (`listenerClassName`, `topicClassName`, `activeInTestMode`,
 * `activeInHeadlessMode`, `os`). The reflection-based field reader walks declared fields so
 * any class with matching field names works.
 */
class ListenerInspectorReflectionTest {

    @Test
    fun `toListenerInfo reads every field on the happy path`() {
        val ld = FakeListenerDescriptor(
            listenerClassName = "com.example.MyListener",
            topicClassName = "com.example.MyTopic",
            activeInTestMode = false,
            activeInHeadlessMode = false,
            os = FakeOs.WINDOWS,
        )
        val info = ListenerInspector.toListenerInfo(ld, "application", "com.example.plugin", "Example Plugin")
        assertNotNull(info)
        assertEquals("com.example.MyListener", info!!.listenerClass)
        assertEquals("com.example.MyTopic", info.topicClass)
        assertEquals("application", info.area)
        assertFalse(info.activeInTestMode)
        assertFalse(info.activeInHeadlessMode)
        assertEquals("WINDOWS", info.os)
        assertEquals("com.example.plugin", info.providedByPluginId)
        assertEquals("Example Plugin", info.providedByPluginName)
    }

    @Test
    fun `toListenerInfo returns null when listenerClassName is missing`() {
        val ld = FakeListenerDescriptorMissingListener(
            topicClassName = "com.example.MyTopic",
            activeInTestMode = true,
            activeInHeadlessMode = true,
        )
        assertNull(ListenerInspector.toListenerInfo(ld, "application", "p", null))
    }

    @Test
    fun `toListenerInfo returns null when topicClassName is missing`() {
        val ld = FakeListenerDescriptorMissingTopic(
            listenerClassName = "com.example.MyListener",
            activeInTestMode = true,
            activeInHeadlessMode = true,
        )
        assertNull(ListenerInspector.toListenerInfo(ld, "application", "p", null))
    }

    @Test
    fun `toListenerInfo defaults activeIn flags to true when fields are absent`() {
        val ld = MinimalListenerDescriptor(
            listenerClassName = "com.example.MyListener",
            topicClassName = "com.example.MyTopic",
        )
        val info = ListenerInspector.toListenerInfo(ld, "project", "p", null)
        assertNotNull(info)
        assertTrue("activeInTestMode defaults to true when missing", info!!.activeInTestMode)
        assertTrue("activeInHeadlessMode defaults to true when missing", info.activeInHeadlessMode)
        assertNull(info.os)
        assertEquals("project", info.area)
    }

    @Test
    fun `toListenerInfo accepts area tag verbatim`() {
        val ld = FakeListenerDescriptor(
            listenerClassName = "com.example.X",
            topicClassName = "com.example.Y",
            activeInTestMode = true,
            activeInHeadlessMode = true,
            os = null,
        )
        val info = ListenerInspector.toListenerInfo(ld, "project", "p", null)
        assertEquals("project", info!!.area)
        assertNull(info.os)
    }
}

// ========================================================================================
// Fixtures
// ========================================================================================

private enum class FakeOs { WINDOWS, MAC, LINUX }

@Suppress("unused")
private class FakeListenerDescriptor(
    @JvmField val listenerClassName: String,
    @JvmField val topicClassName: String,
    @JvmField val activeInTestMode: Boolean,
    @JvmField val activeInHeadlessMode: Boolean,
    @JvmField val os: FakeOs?,
)

@Suppress("unused")
private class FakeListenerDescriptorMissingListener(
    @JvmField val topicClassName: String,
    @JvmField val activeInTestMode: Boolean,
    @JvmField val activeInHeadlessMode: Boolean,
)

@Suppress("unused")
private class FakeListenerDescriptorMissingTopic(
    @JvmField val listenerClassName: String,
    @JvmField val activeInTestMode: Boolean,
    @JvmField val activeInHeadlessMode: Boolean,
)

@Suppress("unused")
private class MinimalListenerDescriptor(
    @JvmField val listenerClassName: String,
    @JvmField val topicClassName: String,
)
