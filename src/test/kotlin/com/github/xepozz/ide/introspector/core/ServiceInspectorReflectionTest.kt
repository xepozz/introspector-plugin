package com.github.xepozz.ide.introspector.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.ServiceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ServiceInspector]'s pure-Kotlin helpers (no IntelliJ test fixture needed).
 *
 * Mirrors the [ExtensionPointInspectorReflectionTest] style: build synthetic doubles, hit the
 * helper directly, assert on the output. The full `listAll()` / `listLightInstantiated()` paths
 * are covered by the platform test.
 */
class ServiceInspectorReflectionTest {

    // ====================================================================================
    // toServiceInfo — happy path
    // ====================================================================================

    @Test
    fun `toServiceInfo carries every ServiceDescriptor field through`() {
        val sd = ServiceDescriptor(
            /* serviceInterface = */ "com.example.Iface",
            /* serviceImplementation = */ "com.example.Impl",
            /* testServiceImplementation = */ "com.example.TestImpl",
            /* headlessImplementation = */ "com.example.HeadlessImpl",
            /* overrides = */ true,
            /* configurationSchemaKey = */ "schema.key",
            /* preload = */ ServiceDescriptor.PreloadMode.AWAIT,
            /* client = */ null,
            /* os = */ null,
        )
        val info = ServiceInspector.toServiceInfo(sd, "application", "com.example.plugin", "Example Plugin")
        assertNotNull(info)
        assertEquals("com.example.Iface", info!!.interfaceClass)
        assertEquals("com.example.Impl", info.implementationClass)
        assertEquals("com.example.TestImpl", info.testServiceImplementation)
        assertEquals("com.example.HeadlessImpl", info.headlessImplementation)
        assertEquals("application", info.area)
        assertEquals("AWAIT", info.preload)
        assertTrue(info.overrides)
        assertEquals("schema.key", info.configurationSchemaKey)
        assertEquals("com.example.plugin", info.providedByPluginId)
        assertEquals("Example Plugin", info.providedByPluginName)
        assertEquals("xml", info.source)
    }

    @Test
    fun `toServiceInfo defaults preload to FALSE when set explicitly`() {
        val sd = ServiceDescriptor(
            null, "com.example.Impl", null, null, false, null,
            ServiceDescriptor.PreloadMode.FALSE, null, null,
        )
        val info = ServiceInspector.toServiceInfo(sd, "project", "p", null)
        assertEquals("FALSE", info!!.preload)
        assertEquals("project", info.area)
        assertNull(info.interfaceClass)
        assertNull(info.client)
        assertNull(info.os)
    }

    @Test
    fun `toServiceInfo falls back to testServiceImplementation when serviceImplementation is null`() {
        val sd = ServiceDescriptor(
            null, null, "com.example.TestOnly", null, false, null,
            ServiceDescriptor.PreloadMode.FALSE, null, null,
        )
        val info = ServiceInspector.toServiceInfo(sd, "application", "p", null)
        assertEquals("com.example.TestOnly", info!!.implementationClass)
    }

    @Test
    fun `toServiceInfo falls back to headlessImplementation as last resort`() {
        val sd = ServiceDescriptor(
            null, null, null, "com.example.HeadlessOnly", false, null,
            ServiceDescriptor.PreloadMode.FALSE, null, null,
        )
        val info = ServiceInspector.toServiceInfo(sd, "application", "p", null)
        assertEquals("com.example.HeadlessOnly", info!!.implementationClass)
    }

    @Test
    fun `toServiceInfo returns null when no implementation is set at all`() {
        val sd = ServiceDescriptor(
            "com.example.Iface", null, null, null, false, null,
            ServiceDescriptor.PreloadMode.FALSE, null, null,
        )
        assertNull(ServiceInspector.toServiceInfo(sd, "application", "p", null))
    }

    // ====================================================================================
    // isLightService — final + @Service contract
    // ====================================================================================

    @Test
    fun `isLightService accepts final class with @Service`() {
        assertTrue(ServiceInspector.isLightService(FinalAnnotatedService::class.java))
    }

    @Test
    fun `isLightService rejects non-final class even with @Service`() {
        assertFalse(ServiceInspector.isLightService(OpenAnnotatedService::class.java))
    }

    @Test
    fun `isLightService rejects final class without @Service`() {
        assertFalse(ServiceInspector.isLightService(FinalPlainClass::class.java))
    }

    // ====================================================================================
    // lightServiceArea — @Service(value) mapping
    // ====================================================================================

    @Test
    fun `lightServiceArea returns application by default for @Service with no level`() {
        assertEquals("application", ServiceInspector.lightServiceArea(FinalAnnotatedService::class.java))
    }

    @Test
    fun `lightServiceArea returns application for @Service(APP)`() {
        assertEquals("application", ServiceInspector.lightServiceArea(AppLevelService::class.java))
    }

    @Test
    fun `lightServiceArea returns project for @Service(PROJECT)`() {
        assertEquals("project", ServiceInspector.lightServiceArea(ProjectLevelService::class.java))
    }

    @Test
    fun `lightServiceArea returns application when class has no @Service annotation`() {
        // The lookup path is never reached for non-light services in production, but the helper
        // is defensive and falls back to "application".
        assertEquals("application", ServiceInspector.lightServiceArea(FinalPlainClass::class.java))
    }
}

// ========================================================================================
// Fixtures
// ========================================================================================

@Service
private class FinalAnnotatedService

@Service
private open class OpenAnnotatedService

private class FinalPlainClass

@Service(Service.Level.APP)
private class AppLevelService

@Service(Service.Level.PROJECT)
private class ProjectLevelService
