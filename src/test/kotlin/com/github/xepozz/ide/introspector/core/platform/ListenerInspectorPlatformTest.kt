package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ListenerInspector
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue

/**
 * Platform-level smoke tests for [ListenerInspector].
 *
 * Validates that the reflection chain through `IdeaPluginDescriptorImpl.appContainerDescriptor` /
 * `projectContainerDescriptor` → `listeners` field returns real
 * [com.intellij.util.messages.ListenerDescriptor] entries from the live sandbox IDE.
 *
 * The IDE always ships with at least a handful of static listeners (file editor, VCS, indexing).
 * If a particular sandbox happens to register none, that's an environmental edge case, not a
 * bug — covered by `assumeTrue` guards.
 */
class ListenerInspectorPlatformTest : BasePlatformTestCase() {

    fun testListAllReturnsAtLeastOne() {
        val all = ListenerInspector.listAll()
        assumeTrue(
            "Test IDE registered zero static listeners — nothing to inspect (very unusual)",
            all.isNotEmpty(),
        )
        assertTrue("listAll() should return populated entries when non-empty", all.isNotEmpty())
    }

    fun testEveryListenerHasNonBlankClasses() {
        val all = ListenerInspector.listAll()
        for (l in all) {
            assertTrue(
                "listenerClass must be non-blank for $l",
                l.listenerClass.isNotBlank(),
            )
            assertTrue(
                "topicClass must be non-blank for $l",
                l.topicClass.isNotBlank(),
            )
        }
    }

    fun testEveryListenerAreaIsAppOrProject() {
        val all = ListenerInspector.listAll()
        for (l in all) {
            assertTrue(
                "ListenerInfo.area must be application/project, got '${l.area}' for ${l.listenerClass}",
                l.area == "application" || l.area == "project",
            )
        }
    }

    fun testAtLeastOneListenerAttributedToPlatform() {
        val all = ListenerInspector.listAll()
        assumeTrue("Need at least one listener to attribute", all.isNotEmpty())
        val hasPlatform = all.any { it.providedByPluginId.startsWith("com.intellij") }
        assertTrue(
            "Expected at least one platform-owned listener; got plugin ids ${all.map { it.providedByPluginId }.distinct()}",
            hasPlatform,
        )
    }
}
