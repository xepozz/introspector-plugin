package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ExtensionPointInspector
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue

/**
 * Platform-level tests for [ExtensionPointInspector].
 *
 * Uses [BasePlatformTestCase] so a light project is always open — that way the
 * "project" and "both" area listings actually have data to enumerate.
 *
 * These tests are regression guards for a few historically fragile reflection paths
 * (pluginDescriptor as a public field rather than a getter, kind/className extraction
 * via [com.intellij.openapi.extensions.impl.ExtensionPointImpl]) and for the rule
 * baked into the inspector that we never call `ep.extensionList` directly — see the
 * CLAUDE.md note about `BuildManager$BuildManagerStartupActivity` for why.
 */
class ExtensionPointInspectorPlatformTest : BasePlatformTestCase() {

    fun testListExtensionPointsApplicationReturnsNonEmpty() {
        val app = ExtensionPointInspector.listExtensionPoints("application")
        assertFalse("Expected application-level EPs to be registered in the test IDE", app.isEmpty())
    }

    fun testListExtensionPointsProjectReturnsNonEmpty() {
        val project = ExtensionPointInspector.listExtensionPoints("project")
        // BasePlatformTestCase guarantees a project is open; if for some reason this run
        // lacks one, treat that as an environmental skip rather than a hard failure.
        assumeTrue(
            "No open project in this test sandbox — cannot validate project-area EPs",
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects.isNotEmpty(),
        )
        assertFalse("Expected project-level EPs once a project is open", project.isEmpty())
    }

    fun testListExtensionPointsBothIsAtLeastAsLargeAsApplication() {
        val app = ExtensionPointInspector.listExtensionPoints("application")
        val both = ExtensionPointInspector.listExtensionPoints("both")
        assertTrue(
            "both (${both.size}) must contain at least application (${app.size}) entries",
            both.size >= app.size,
        )
    }

    fun testEpInfoHasNonUnknownPluginIdForPlatformEps() {
        val app = ExtensionPointInspector.listExtensionPoints("application")
        val hasIntellijOwned = app.any { it.declaredByPluginId.startsWith("com.intellij") }
        assertTrue(
            "At least one application EP must be attributed to a com.intellij plugin; got " +
                app.map { it.declaredByPluginId }.distinct(),
            hasIntellijOwned,
        )
    }

    fun testEpKindIsInterfaceOrBeanClass() {
        val app = ExtensionPointInspector.listExtensionPoints("application")
        for (ep in app) {
            assertTrue(
                "EP ${ep.name} has unexpected kind=${ep.kind}; only INTERFACE / BEAN_CLASS are valid",
                ep.kind == "INTERFACE" || ep.kind == "BEAN_CLASS",
            )
        }
    }

    fun testListExtensionsForUnknownEpReturnsEmpty() {
        val result = ExtensionPointInspector.listExtensionsForEp("com.example.does.not.exist", 100)
        assertTrue(
            "Unknown EP name must return an empty list, got ${result.size} entries",
            result.isEmpty(),
        )
    }

    fun testListExtensionsForRealEpReturnsAdaptersWithoutBlowingUp() {
        val app = ExtensionPointInspector.listExtensionPoints("application")
        // Pick the first EP we know has registered extensions. Many EPs in the sandbox
        // are declared but unused — those would short-circuit to an empty list and not
        // exercise the adapter-reflection path we care about here.
        val populated = app.firstOrNull { it.extensionsCount > 0 }
        assumeTrue(
            "No application EP with extensionsCount > 0 in this IDE build — nothing to inspect",
            populated != null,
        )
        val name = populated!!.name
        val extensions = ExtensionPointInspector.listExtensionsForEp(name, limit = 10)
        assertFalse(
            "Expected at least one extension for $name (extensionsCount=${populated.extensionsCount})",
            extensions.isEmpty(),
        )
        val hasDetail = extensions.any { it.implementationClass != null || it.additionalAttributes.isNotEmpty() }
        assertTrue(
            "At least one extension for $name must expose an implementationClass or XML attributes; " +
                "if every entry is bare, the reflection chain through getSortedAdapters is broken",
            hasDetail,
        )
    }
}
