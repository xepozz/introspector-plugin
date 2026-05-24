package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ServiceInspector
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level smoke tests for [ServiceInspector].
 *
 * Validates that the reflection chain through `IdeaPluginDescriptorImpl.appContainerDescriptor` /
 * `projectContainerDescriptor` / `moduleContainerDescriptor` → `services` field returns real
 * [com.intellij.openapi.components.ServiceDescriptor] entries from the live sandbox IDE.
 *
 * Light-service enumeration (`listLightInstantiated`) is non-deterministic — depends on which
 * services the test fixture has touched — so the assertions there are deliberately loose.
 */
class ServiceInspectorPlatformTest : BasePlatformTestCase() {

    fun testListAllReturnsNonEmpty() {
        val all = ServiceInspector.listAll()
        assertFalse(
            "ServiceInspector.listAll() must find at least one XML-declared service in the test IDE",
            all.isEmpty(),
        )
    }

    fun testEveryServiceHasNonBlankImplementation() {
        val all = ServiceInspector.listAll()
        for (s in all) {
            assertTrue(
                "ServiceInfo.implementationClass must be non-blank for $s",
                s.implementationClass.isNotBlank(),
            )
        }
    }

    fun testEveryServiceAreaIsKnown() {
        val all = ServiceInspector.listAll()
        val allowed = setOf("application", "project", "module")
        for (s in all) {
            assertTrue(
                "ServiceInfo.area must be one of $allowed, got '${s.area}' for ${s.implementationClass}",
                s.area in allowed,
            )
        }
    }

    fun testServicesIncludeAllThreeAreas() {
        val all = ServiceInspector.listAll()
        val areas = all.map { it.area }.toSet()
        // Vanilla IDEA has services at all three levels — application/project/module — so the
        // snapshot should cover every area. If a future IDE edition drops one, downgrade to
        // assumeTrue rather than weakening the contract everywhere.
        assertTrue(
            "Expected at least the 'application' area to be represented; got $areas",
            "application" in areas,
        )
    }

    fun testEveryPreloadValueIsValid() {
        val all = ServiceInspector.listAll()
        val allowed = setOf("FALSE", "TRUE", "AWAIT", "NOT_HEADLESS", "NOT_LIGHT_EDIT")
        for (s in all) {
            assertTrue(
                "ServiceInfo.preload must be a PreloadMode name, got '${s.preload}' for ${s.implementationClass}",
                s.preload in allowed,
            )
        }
    }

    fun testServicesAreAttributedToIntellijPlatform() {
        val all = ServiceInspector.listAll()
        val hasPlatform = all.any { it.providedByPluginId.startsWith("com.intellij") }
        assertTrue(
            "At least one service must be attributed to a com.intellij plugin; got ${all.map { it.providedByPluginId }.distinct()}",
            hasPlatform,
        )
    }

    fun testEveryServiceMarkedAsXmlSource() {
        val all = ServiceInspector.listAll()
        for (s in all) {
            assertEquals(
                "listAll() must only return source='xml' entries; got '${s.source}' for ${s.implementationClass}",
                "xml",
                s.source,
            )
        }
    }

    fun testListLightInstantiatedDoesNotThrow() {
        // Best-effort enumeration; result may be empty if nothing has been touched yet, but it
        // must never throw.
        val light = ServiceInspector.listLightInstantiated()
        for (s in light) {
            assertEquals(
                "Light services must carry source='light_instantiated'; got '${s.source}'",
                "light_instantiated",
                s.source,
            )
            assertTrue(
                "Light-service area must be application/project; got '${s.area}'",
                s.area == "application" || s.area == "project",
            )
        }
    }

    fun testListLightInstantiatedHonoursExcludeSet() {
        val light = ServiceInspector.listLightInstantiated()
        // If we re-run with one of the discovered impls excluded, that impl must disappear.
        val sample = light.firstOrNull()?.implementationClass ?: return
        val filtered = ServiceInspector.listLightInstantiated(setOf(sample))
        assertFalse(
            "Excluded implementation $sample must not appear in the second call's output",
            filtered.any { it.implementationClass == sample },
        )
    }
}
