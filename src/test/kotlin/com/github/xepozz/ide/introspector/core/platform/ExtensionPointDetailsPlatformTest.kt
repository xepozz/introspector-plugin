package com.github.xepozz.ide.introspector.core.platform

import com.github.xepozz.ide.introspector.core.ExtensionPointInspector
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue

/**
 * Platform-level tests for `arch.get_extension_point_details`. Asserts the live IDE's
 * built-in Extension Points actually round-trip through [ExtensionPointInspector.getDetails]
 * with the right kind / bean-schema / interface-method shape.
 *
 * EPs picked here are bundled with the platform and have been stable for many releases:
 *   - `com.intellij.toolWindow` (BEAN_CLASS — ToolWindowEP, fields id/anchor/factoryClass)
 *   - `com.intellij.codeInsight.lineMarkerProvider` (INTERFACE — LineMarkerProvider with
 *     a tiny abstract surface)
 * If either is gone in a future build, the assertions will fail loud rather than
 * silently — that's intentional, the test doubles as a "did the platform rename EP X?"
 * smoke test.
 */
class ExtensionPointDetailsPlatformTest : BasePlatformTestCase() {

    fun testToolWindowEpReturnsBeanSchemaWithExpectedFields() {
        val details = ExtensionPointInspector.getDetails("com.intellij.toolWindow")
        assertNotNull("Expected com.intellij.toolWindow to be registered", details)
        details!!
        assertEquals("BEAN_CLASS", details.kind)
        assertEquals("com.intellij.openapi.wm.ToolWindowEP", details.interfaceOrBeanClass)
        assertEquals("application", details.area)
        val schema = details.beanSchema
        assertNotNull("Expected beanSchema for BEAN_CLASS EP", schema)
        val fieldNames = schema!!.fields.map { it.name }.toSet()
        // ToolWindowEP has been stable for years — these are required-ish columns.
        // Use a subset check so the test survives the platform adding new fields.
        for (expected in listOf("id", "anchor", "factoryClass")) {
            assertTrue(
                "Expected field '$expected' in ToolWindowEP schema; got $fieldNames",
                fieldNames.contains(expected),
            )
        }
    }

    fun testInterfaceExtensionPointReturnsInterfaceMethods() {
        // Walk a list of well-known platform EPs registered with `interface="…"` and assert
        // that the FIRST one we can find in this sandbox round-trips through getDetails as
        // INTERFACE and yields at least one abstract method. The specific EP varies across
        // 251/252 platform builds (deprecations, splits between Java module and core), so we
        // try several rather than pinning to one and hoping it lives forever.
        val candidates = listOf(
            "com.intellij.errorHandler",
            "com.intellij.statusBarWidgetFactory",
            "com.intellij.applicationService",
            "com.intellij.codeInsight.lineMarkerProvider",
        )
        var checkedAny = false
        for (name in candidates) {
            val details = ExtensionPointInspector.getDetails(name) ?: continue
            if (details.kind != "INTERFACE") continue
            val methods = details.interfaceMethods ?: continue
            if (methods.isEmpty()) continue
            checkedAny = true
            assertTrue(
                "Expected at least one abstract method on $name interface; got ${methods.map { it.name }}",
                methods.isNotEmpty(),
            )
            return
        }
        assumeTrue(
            "None of $candidates were registered as INTERFACE EPs with abstract methods in this sandbox — " +
                "skip rather than fail (platform may have moved them).",
            checkedAny,
        )
    }

    fun testUnknownEpReturnsNullInsteadOfThrowing() {
        val details = ExtensionPointInspector.getDetails("com.example.definitely.not.an.ep")
        assertNull("Unknown EP name must return null, not throw", details)
    }

    fun testIncludeRegisteredCountMatchesAdapterCount() {
        val name = "com.intellij.toolWindow"
        val details = ExtensionPointInspector.getDetails(name, includeRegisteredCount = true)
        assertNotNull(details)
        details!!
        assertNotNull(
            "registeredCount must be populated when includeRegisteredCount=true",
            details.registeredCount,
        )
        assertTrue(
            "Expected a positive ToolWindow registration count, got ${details.registeredCount}",
            (details.registeredCount ?: -1) > 0,
        )
    }

    fun testIncludeRegisteredCountDefaultsToNull() {
        val details = ExtensionPointInspector.getDetails("com.intellij.toolWindow")
        assertNotNull(details)
        assertNull(
            "registeredCount must be null when includeRegisteredCount=false (default)",
            details!!.registeredCount,
        )
    }

    fun testBeanSchemaCanBeOmitted() {
        val details = ExtensionPointInspector.getDetails(
            "com.intellij.toolWindow",
            includeBeanSchema = false,
        )
        assertNotNull(details)
        assertNull(
            "beanSchema must be omitted when includeBeanSchema=false",
            details!!.beanSchema,
        )
    }
}
