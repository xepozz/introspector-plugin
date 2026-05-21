package com.github.xepozz.ide.introspector.core

import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.impl.ExtensionComponentAdapter
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.BiPredicate
import java.util.function.Function

/**
 * Pure-JVM reflection tests for [ExtensionPointInspector] — no IntelliJ test fixture needed.
 *
 * The platform tests under `core/platform/` only exercise the happy paths against a live IDE
 * (real [ExtensionsArea] / [ExtensionPointImpl] shapes). The reflection fallbacks in this file
 * exist to survive IDE version drift — they fire when methods/fields are named differently or
 * absent entirely — and those branches are unreachable without synthetic doubles.
 *
 * Two patterns are used here:
 *   1. **Direct call** for helpers whose parameter is `Any` (e.g. [ExtensionPointInspector.readMethod],
 *      [ExtensionPointInspector.readField], [ExtensionPointInspector.extractPluginIdString],
 *      [ExtensionPointInspector.readAdditionalAttributes]). We pass synthetic Kotlin classes
 *      with the exact field/method shapes we want to exercise.
 *   2. **Subclass an interface stub** for helpers whose parameter is [ExtensionPoint] or
 *      [ExtensionsArea]. [StubExtensionPoint] / [StubExtensionsArea] satisfy the typed
 *      parameter contract with no-op stubs; concrete subclasses then add the methods/fields
 *      under test.
 */
class ExtensionPointInspectorReflectionTest {

    // ====================================================================================
    // SECTION 1. readMethod
    // ====================================================================================

    @Test
    fun `readMethod finds a zero-arg method by name`() {
        val target = ObjectWithReturningMethod()
        assertEquals("hello", ExtensionPointInspector.readMethod(target, "greet"))
    }

    @Test
    fun `readMethod returns null when no method by that name`() {
        assertNull(ExtensionPointInspector.readMethod(ObjectWithReturningMethod(), "missing"))
    }

    @Test
    fun `readMethod returns null when the method throws`() {
        assertNull(ExtensionPointInspector.readMethod(ObjectWithThrowingMethod(), "boom"))
    }

    @Test
    fun `readMethod ignores methods that take parameters`() {
        // `greetWithArg(String)` exists but has a parameter; helper only picks 0-arg methods.
        assertNull(ExtensionPointInspector.readMethod(ObjectWithParamMethod(), "greetWithArg"))
    }

    // ====================================================================================
    // SECTION 2. readField
    // ====================================================================================

    @Test
    fun `readField finds a field on the direct class`() {
        val target = ObjectWithDirectField()
        assertEquals("direct", ExtensionPointInspector.readField(target, "value"))
    }

    @Test
    fun `readField walks the superclass when the field is not on the direct class`() {
        val target = ChildWithoutOwnField()
        assertEquals("parent-value", ExtensionPointInspector.readField(target, "parentValue"))
    }

    @Test
    fun `readField returns null when no field with that name exists anywhere`() {
        assertNull(ExtensionPointInspector.readField(ObjectWithDirectField(), "absent"))
    }

    @Test
    fun `readField reads a private field via setAccessible`() {
        // Kotlin `private` lowers to a JVM private field; the helper calls setAccessible(true)
        // before reading, so it should still resolve.
        assertEquals("secret", ExtensionPointInspector.readField(ObjectWithPrivateField(), "secret"))
    }

    // ====================================================================================
    // SECTION 3. extractPluginIdString
    // ====================================================================================

    @Test
    fun `extractPluginIdString resolves via getPluginId then getIdString`() {
        val pd = DescriptorWithPluginIdGetter("com.example.real")
        assertEquals("com.example.real", ExtensionPointInspector.extractPluginIdString(pd))
    }

    @Test
    fun `extractPluginIdString falls back to pluginId field when getter absent`() {
        val pd = DescriptorWithPluginIdField(PluginIdWithIdStringGetter("com.example.via-field"))
        assertEquals("com.example.via-field", ExtensionPointInspector.extractPluginIdString(pd))
    }

    @Test
    fun `extractPluginIdString falls back to idString field when getIdString absent`() {
        val pd = DescriptorWithPluginIdGetter2(PluginIdWithIdStringField("com.example.field-id"))
        assertEquals("com.example.field-id", ExtensionPointInspector.extractPluginIdString(pd))
    }

    @Test
    fun `extractPluginIdString falls back to PluginId toString when neither getter nor field present`() {
        val pd = DescriptorWithPluginIdGetter2(PluginIdWithToString("com.example.tostring-id"))
        assertEquals("com.example.tostring-id", ExtensionPointInspector.extractPluginIdString(pd))
    }

    @Test
    fun `extractPluginIdString returns null when descriptor lacks both getter and field`() {
        assertNull(ExtensionPointInspector.extractPluginIdString(EmptyDescriptor()))
    }

    // ====================================================================================
    // SECTION 4. epName
    // ====================================================================================

    @Test
    fun `epName resolves via the public name field`() {
        assertEquals("com.example.byField", ExtensionPointInspector.epName(EpWithPublicNameField()))
    }

    @Test
    fun `epName falls back to getName() when no name field`() {
        assertEquals("com.example.byGetter", ExtensionPointInspector.epName(EpWithGetNameOnly()))
    }

    @Test
    fun `epName falls back to simpleName when neither field nor getter exist`() {
        assertEquals("EpWithoutAnyName", ExtensionPointInspector.epName(EpWithoutAnyName()))
    }

    @Test
    fun `epName falls back to simpleName when name field access throws`() {
        // The `fields.firstOrNull` finds the field; reading it triggers ExceptionInInitializerError-
        // adjacent failure (here: a custom getter throws). The catch-all returns simpleName.
        assertEquals("EpWithThrowingNameField", ExtensionPointInspector.epName(EpWithThrowingNameField()))
    }

    // ====================================================================================
    // SECTION 5. kindAndClass
    // ====================================================================================

    @Test
    fun `kindAndClass returns INTERFACE when getKind returns INTERFACE`() {
        val (kind, _) = ExtensionPointInspector.kindAndClass(EpWithKindInterfaceAndClassName())
        assertEquals("INTERFACE", kind)
    }

    @Test
    fun `kindAndClass returns BEAN_CLASS when getKind returns BEAN_CLASS`() {
        val (kind, _) = ExtensionPointInspector.kindAndClass(EpWithKindBean())
        assertEquals("BEAN_CLASS", kind)
    }

    @Test
    fun `kindAndClass preserves the raw kind toString for unknown values`() {
        val (kind, _) = ExtensionPointInspector.kindAndClass(EpWithKindCustom())
        assertEquals("MY_CUSTOM_KIND", kind)
    }

    @Test
    fun `kindAndClass defaults to BEAN_CLASS when getKind absent`() {
        val (kind, _) = ExtensionPointInspector.kindAndClass(EpWithoutKindMethod())
        assertEquals("BEAN_CLASS", kind)
    }

    @Test
    fun `kindAndClass resolves className via the className field fallback`() {
        val (_, cls) = ExtensionPointInspector.kindAndClass(EpWithClassNameField())
        assertEquals("com.example.ByClassName", cls)
    }

    @Test
    fun `kindAndClass resolves className via getExtensionClass when fields absent`() {
        val (_, cls) = ExtensionPointInspector.kindAndClass(EpWithGetExtensionClass())
        assertEquals("java.lang.String", cls)
    }

    @Test
    fun `kindAndClass returns the question-mark sentinel when className cannot be resolved`() {
        val (_, cls) = ExtensionPointInspector.kindAndClass(EpWithoutAnyClassResolution())
        assertEquals("?", cls)
    }

    // ====================================================================================
    // SECTION 6. tryReadClassNameField
    // ====================================================================================

    @Test
    fun `tryReadClassNameField picks up the className field`() {
        assertEquals(
            "com.example.ByClassName",
            ExtensionPointInspector.tryReadClassNameField(EpWithClassNameField()),
        )
    }

    @Test
    fun `tryReadClassNameField falls back to myClassName when className absent`() {
        assertEquals(
            "com.example.ByMyClassName",
            ExtensionPointInspector.tryReadClassNameField(EpWithMyClassNameField()),
        )
    }

    @Test
    fun `tryReadClassNameField walks the superclass chain`() {
        assertEquals(
            "com.example.OnParent",
            ExtensionPointInspector.tryReadClassNameField(ChildEpInheritingClassName()),
        )
    }

    @Test
    fun `tryReadClassNameField returns null when no className-style field exists`() {
        assertNull(ExtensionPointInspector.tryReadClassNameField(EpWithoutAnyClassResolution()))
    }

    // ====================================================================================
    // SECTION 7. tryReadExtensionClass
    // ====================================================================================

    @Test
    fun `tryReadExtensionClass returns the Class name on the happy path`() {
        assertEquals(
            "java.lang.String",
            ExtensionPointInspector.tryReadExtensionClass(EpWithGetExtensionClass()),
        )
    }

    @Test
    fun `tryReadExtensionClass returns null when getExtensionClass absent`() {
        assertNull(ExtensionPointInspector.tryReadExtensionClass(EpWithoutAnyClassResolution()))
    }

    @Test
    fun `tryReadExtensionClass returns null when getExtensionClass throws`() {
        assertNull(ExtensionPointInspector.tryReadExtensionClass(EpWithGetExtensionClassThrowing()))
    }

    @Test
    fun `tryReadExtensionClass returns null when getExtensionClass returns a non-Class`() {
        // Method exists with the right name + arity but the `as? Class<*>` cast yields null.
        assertNull(ExtensionPointInspector.tryReadExtensionClass(EpWithGetExtensionClassWrongType()))
    }

    // ====================================================================================
    // SECTION 8. pluginDescriptorOf
    // ====================================================================================

    @Test
    fun `pluginDescriptorOf resolves id and name via getPluginDescriptor`() {
        val result = ExtensionPointInspector.pluginDescriptorOf(EpWithPluginDescriptor())
        assertNotNull(result)
        assertEquals("com.example.from-descriptor", result!!.first)
        assertEquals("Example Plugin", result.second)
    }

    @Test
    fun `pluginDescriptorOf returns null when the descriptor accessor throws`() {
        assertNull(ExtensionPointInspector.pluginDescriptorOf(EpWithThrowingDescriptor()))
    }

    @Test
    fun `pluginDescriptorOf surfaces null displayName when descriptor exposes none`() {
        val result = ExtensionPointInspector.pluginDescriptorOf(EpWithPluginDescriptorNullName())
        assertNotNull(result)
        assertEquals("com.example.no-name", result!!.first)
        assertNull(result.second)
    }

    @Test
    fun `pluginDescriptorOf returns unknown id when descriptor getPluginId returns null`() {
        // The proxy below routes getPluginId() to null, exercising the `?: "unknown"` fallback.
        val result = ExtensionPointInspector.pluginDescriptorOf(EpWithPluginDescriptorNullId())
        assertNotNull(result)
        assertEquals("unknown", result!!.first)
    }

    // ====================================================================================
    // SECTION 9b. extensionPointInfoOf — integration across helpers
    // ====================================================================================

    @Test
    fun `extensionPointInfoOf assembles a record from helpers on happy path`() {
        val info = ExtensionPointInspector.extensionPointInfoOf(EpWithEverything(), "application")
        assertEquals("com.example.everything", info.name)
        assertEquals("INTERFACE", info.kind)
        assertEquals("com.example.IFace", info.interfaceOrBeanClass)
        assertEquals("com.example.everything-plugin", info.declaredByPluginId)
        assertEquals("Everything Plugin", info.declaredByPluginName)
        assertTrue("EpWithEverything declares isDynamic=true", info.isDynamic)
        assertEquals(42, info.extensionsCount)
        assertEquals("application", info.area)
    }

    @Test
    fun `extensionPointInfoOf falls back to unknown plugin id when descriptor is unavailable`() {
        // Stub's getPluginDescriptor returns a stub descriptor (id "stub.unused"); we want the
        // "unknown" branch when pluginDescriptorOf itself returns null — easiest path is to
        // make the descriptor accessor throw, which the catch turns into null.
        val info = ExtensionPointInspector.extensionPointInfoOf(EpWithThrowingDescriptor(), "project")
        assertEquals("unknown", info.declaredByPluginId)
        assertNull(info.declaredByPluginName)
        assertEquals("project", info.area)
    }

    @Test
    fun `extensionPointInfoOf surfaces zero extensionsCount when size throws`() {
        val info = ExtensionPointInspector.extensionPointInfoOf(EpWithThrowingSize(), "application")
        assertEquals(0, info.extensionsCount)
    }

    // ====================================================================================
    // SECTION 9. isDynamic
    // ====================================================================================

    @Test
    fun `isDynamic returns true when isDynamic returns true`() {
        assertTrue(
            "expected isDynamic=true for EpWithIsDynamic(true)",
            ExtensionPointInspector.isDynamic(EpWithIsDynamic(true)),
        )
    }

    @Test
    fun `isDynamic returns false when isDynamic returns false`() {
        assertFalse(ExtensionPointInspector.isDynamic(EpWithIsDynamic(false)))
    }

    @Test
    fun `isDynamic returns false when the method throws`() {
        assertFalse(ExtensionPointInspector.isDynamic(EpWithIsDynamicThrowing()))
    }

    // Note: the absence-of-method branch is unreachable for real ExtensionPoint instances because
    // the interface itself declares `isDynamic()`. Our StubExtensionPoint inherits that signature.

    // ====================================================================================
    // SECTION 10. extractAllEps (via the public ExtensionsArea contract)
    // ====================================================================================

    @Test
    fun `extractAllEps reads a Map-typed getExtensionPoints method`() {
        val ep1 = EpWithPublicNameField()
        val ep2 = EpWithGetNameOnly()
        val area = AreaWithMapMethod(mapOf("a" to ep1, "b" to ep2))
        val result = ExtensionPointInspector.extractAllEps(area)
        assertEquals(2, result.size)
        assertTrue("expected ep1 in result, got $result", result.contains(ep1))
        assertTrue("expected ep2 in result, got $result", result.contains(ep2))
    }

    @Test
    fun `extractAllEps reads a Collection-typed getExtensionPoints method`() {
        val ep1 = EpWithPublicNameField()
        val area = AreaWithCollectionMethod(listOf(ep1, "not-an-ep"))
        val result = ExtensionPointInspector.extractAllEps(area)
        // filterIsInstance keeps only the ExtensionPoint entries.
        assertEquals(1, result.size)
        assertTrue("expected ep1 to survive filterIsInstance, got $result", result.contains(ep1))
    }

    @Test
    fun `extractAllEps falls back to the extensionPoints field when no method`() {
        val ep1 = EpWithPublicNameField()
        val area = AreaWithFieldOnly(mapOf("a" to ep1))
        val result = ExtensionPointInspector.extractAllEps(area)
        assertEquals(1, result.size)
        assertTrue("expected ep1 picked up from field fallback, got $result", result.contains(ep1))
    }

    @Test
    fun `extractAllEps returns empty when neither method nor field present`() {
        val area = AreaWithNothing()
        val result = ExtensionPointInspector.extractAllEps(area)
        assertTrue("expected empty result when no method/field exposed, got $result", result.isEmpty())
    }

    @Test
    fun `extractAllEps returns empty when getExtensionPoints returns a wrong type`() {
        val area = AreaWithStringMethod("not-a-collection")
        val result = ExtensionPointInspector.extractAllEps(area)
        assertTrue("expected empty result for non-collection return type, got $result", result.isEmpty())
    }

    // ====================================================================================
    // SECTION 11. readAdditionalAttributes
    // ====================================================================================

    @Test
    fun `readAdditionalAttributes harvests a Map-typed attributes field on extensionElement`() {
        val adapter = AdapterWithElementAttributesMap(
            mapOf("implementation" to "com.example.Impl", "id" to "abc"),
        )
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals("com.example.Impl", result["implementation"])
        assertEquals("abc", result["id"])
    }

    @Test
    fun `readAdditionalAttributes harvests a Map returned by getAttributes method`() {
        val adapter = AdapterWithElementGetAttributesMap(mapOf("language" to "Kotlin"))
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals("Kotlin", result["language"])
    }

    @Test
    fun `readAdditionalAttributes harvests a List of Attribute-like objects`() {
        val adapter = AdapterWithElementGetAttributesList(
            listOf(FakeAttr("group", "x"), FakeAttr("order", "first")),
        )
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals("x", result["group"])
        assertEquals("first", result["order"])
    }

    @Test
    fun `readAdditionalAttributes skips null entries from a list of attributes`() {
        val adapter = AdapterWithElementGetAttributesList(
            listOf(FakeAttr("ok", "yes"), null, FakeAttr("also", "good")),
        )
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals("yes", result["ok"])
        assertEquals("good", result["also"])
    }

    @Test
    fun `readAdditionalAttributes skips attribute entries whose name or value is null`() {
        val adapter = AdapterWithElementGetAttributesList(
            listOf(
                FakeAttrNullable(null, "no-name"),
                FakeAttrNullable("no-value", null),
                FakeAttrNullable("ok", "yes"),
            ),
        )
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals(1, result.size)
        assertEquals("yes", result["ok"])
    }

    @Test
    fun `readAdditionalAttributes skips map entries whose key or value is null`() {
        // Map<*,*> path: production loops with `if (k != null && v != null) merged[…] = …`.
        val adapter = AdapterWithElementAttributesMapNullable(
            linkedMapOf(
                "ok" to "yes",
                null to "no-key",
                "no-value" to null,
            ),
        )
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals(1, result.size)
        assertEquals("yes", result["ok"])
    }

    @Test
    fun `readAdditionalAttributes is robust when extensionInstance access throws`() {
        // Both the element AND instance reads fail; both catch blocks swallow.
        val result = ExtensionPointInspector.readAdditionalAttributes(AdapterWithThrowingBoth())
        assertTrue("expected empty result, got $result", result.isEmpty())
    }

    @Test
    fun `readAdditionalAttributes harvests bean fields from extensionInstance`() {
        val adapter = AdapterWithInstance(BeanWithAttributes())
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals("com.example.Bean", result["implementation"])
        assertEquals("12", result["order"])
    }

    @Test
    fun `readAdditionalAttributes merges element attributes with bean fields`() {
        // Element attribute "implementation" takes priority; bean adds a separate key "order".
        val adapter = AdapterWithBoth(
            elementAttrs = mapOf("implementation" to "com.example.FromXml"),
            instance = BeanWithAttributes(),
        )
        val result = ExtensionPointInspector.readAdditionalAttributes(adapter)
        assertEquals("com.example.FromXml", result["implementation"])
        // BeanWithAttributes also declares `implementation` — XML must win (harvest preserves existing).
        assertEquals("12", result["order"])
    }

    @Test
    fun `readAdditionalAttributes returns empty map when adapter has neither element nor instance`() {
        val result = ExtensionPointInspector.readAdditionalAttributes(EmptyAdapter())
        assertTrue("expected empty result, got $result", result.isEmpty())
    }

    @Test
    fun `readAdditionalAttributes swallows exceptions thrown while reading the element`() {
        // Custom getter throws → the catch block swallows; we still get an empty (or bean-only) result.
        val result = ExtensionPointInspector.readAdditionalAttributes(AdapterWithThrowingElement())
        assertTrue("expected empty result after element throw, got $result", result.isEmpty())
    }
}

// ========================================================================================
// SECTION 12. Fixtures — simple Any-typed targets
// ========================================================================================

private class ObjectWithReturningMethod {
    fun greet(): String = "hello"
}

private class ObjectWithThrowingMethod {
    fun boom(): String = throw IllegalStateException("nope")
}

private class ObjectWithParamMethod {
    @Suppress("unused") fun greetWithArg(name: String): String = "hi $name"
}

private class ObjectWithDirectField {
    @JvmField val value: String = "direct"
}

private open class ParentWithField {
    @JvmField val parentValue: String = "parent-value"
}
private class ChildWithoutOwnField : ParentWithField()

private class ObjectWithPrivateField {
    @Suppress("unused") private val secret: String = "secret"
}

// ========================================================================================
// SECTION 13. Fixtures — descriptors for extractPluginIdString
// ========================================================================================

private class DescriptorWithPluginIdGetter(private val id: String) {
    fun getPluginId(): PluginIdWithIdStringGetter = PluginIdWithIdStringGetter(id)
}
private class DescriptorWithPluginIdField(@JvmField val pluginId: Any)
private class DescriptorWithPluginIdGetter2(private val id: Any) {
    fun getPluginId(): Any = id
}
private class EmptyDescriptor

private class PluginIdWithIdStringGetter(private val id: String) {
    fun getIdString(): String = id
}
private class PluginIdWithIdStringField(@JvmField val idString: String)
private class PluginIdWithToString(private val id: String) {
    override fun toString(): String = id
}

// ========================================================================================
// SECTION 14. Fixtures — synthetic ExtensionPoint subclasses
// ========================================================================================

/**
 * Stub base for ExtensionPoint with no-op implementations. Subclasses ADD the methods/fields
 * the production helpers reflectively probe — the stub's job is just to satisfy the type system.
 */
private abstract class StubExtensionPoint : ExtensionPoint<Any> {
    @Deprecated("Stub override of a deprecated interface member.")
    override fun registerExtension(extension: Any) {}
    override fun registerExtension(extension: Any, parentDisposable: com.intellij.openapi.Disposable) {}
    override fun registerExtension(
        extension: Any,
        pluginDescriptor: PluginDescriptor,
        parentDisposable: com.intellij.openapi.Disposable,
    ) {}
    override fun registerExtension(
        extension: Any,
        order: LoadingOrder,
        parentDisposable: com.intellij.openapi.Disposable,
    ) {}
    override fun getExtensions(): Array<Any> = emptyArray()
    override fun getExtensionList(): MutableList<Any> = mutableListOf()
    override fun size(): Int = 0
    @Deprecated("Stub override of a deprecated interface member.")
    override fun unregisterExtension(extension: Any) {}
    override fun unregisterExtension(extensionClass: Class<out Any>) {}
    override fun unregisterExtensions(
        filter: BiPredicate<String, ExtensionComponentAdapter>,
        stopAfterFirstMatch: Boolean,
    ): Boolean = false
    @Deprecated("Stub override of a deprecated interface member.")
    override fun addExtensionPointListener(
        listener: ExtensionPointListener<Any>,
        invokeForLoadedExtensions: Boolean,
        parentDisposable: com.intellij.openapi.Disposable?,
    ) {}
    override fun addExtensionPointListener(
        coroutineScope: CoroutineScope,
        invokeForLoadedExtensions: Boolean,
        listener: ExtensionPointListener<Any>,
    ) {}
    override fun addChangeListener(listener: Runnable, parentDisposable: com.intellij.openapi.Disposable?) {}
    override fun addChangeListener(coroutineScope: CoroutineScope, listener: Runnable) {}
    override fun removeExtensionPointListener(extensionPointListener: ExtensionPointListener<Any>) {}
    override fun isDynamic(): Boolean = false
    override fun getPluginDescriptor(): PluginDescriptor =
        ControllablePluginDescriptor.create("stub.unused", null)
    override fun <K : Any> getByKey(
        key: K,
        cacheId: Class<*>,
        keyMapper: Function<Any, K?>,
    ): Any? = null
}

private class EpWithPublicNameField : StubExtensionPoint() {
    @JvmField val name: String = "com.example.byField"
}

private class EpWithGetNameOnly : StubExtensionPoint() {
    fun getName(): String = "com.example.byGetter"
}

private class EpWithoutAnyName : StubExtensionPoint()

private class EpWithThrowingNameField : StubExtensionPoint() {
    @JvmField val name: Any = object {
        override fun toString(): String = throw IllegalStateException("name boom")
    }
}

private class EpWithKindInterfaceAndClassName : StubExtensionPoint() {
    @JvmField val className: String = "com.example.Interfaced"
    fun getKind(): String = "INTERFACE"
}

private class EpWithKindBean : StubExtensionPoint() {
    @JvmField val className: String = "com.example.BeanCls"
    fun getKind(): String = "BEAN_CLASS"
}

private class EpWithKindCustom : StubExtensionPoint() {
    @JvmField val className: String = "com.example.Custom"
    fun getKind(): String = "MY_CUSTOM_KIND"
}

private class EpWithoutKindMethod : StubExtensionPoint() {
    @JvmField val className: String = "com.example.NoKind"
}

private class EpWithClassNameField : StubExtensionPoint() {
    // Use a synthetic field name "className" but not the typed ExtensionPointImpl path —
    // tryReadClassNameField scans declaredFields directly, so the @JvmField is enough.
    @JvmField val className: String = "com.example.ByClassName"
    fun getKind(): String = "INTERFACE"
}

private class EpWithMyClassNameField : StubExtensionPoint() {
    @JvmField val myClassName: String = "com.example.ByMyClassName"
}

private open class ParentEpWithClassName : StubExtensionPoint() {
    @JvmField val className: String = "com.example.OnParent"
}
private class ChildEpInheritingClassName : ParentEpWithClassName()

private class EpWithGetExtensionClass : StubExtensionPoint() {
    fun getExtensionClass(): Class<*> = String::class.java
}

private class EpWithGetExtensionClassThrowing : StubExtensionPoint() {
    fun getExtensionClass(): Class<*> = throw IllegalStateException("class boom")
}

private class EpWithGetExtensionClassWrongType : StubExtensionPoint() {
    @Suppress("unused") fun getExtensionClass(): String = "not-a-Class"
}

private class EpWithoutAnyClassResolution : StubExtensionPoint()

// ========================================================================================
// SECTION 15. Fixtures — pluginDescriptorOf
// ========================================================================================

private class FakePluginDescriptor(
    private val id: String,
    private val displayName: String?,
) {
    fun getPluginId(): String = id
    fun getName(): String? = displayName
}

private class EpWithPluginDescriptor : StubExtensionPoint() {
    // Note: this hides the inherited interface getPluginDescriptor(): PluginDescriptor at the
    // Java reflection level — `methods` returns both, but the helper picks `firstOrNull` so we
    // have to ensure ours has the right 0-arg shape. The interface version also takes 0 args
    // and returns ThrowingPluginDescriptor (would throw on getPluginId). To avoid that, we
    // override the interface method to return a controllable PluginDescriptor.
    override fun getPluginDescriptor(): PluginDescriptor = ControllablePluginDescriptor.create(
        idString = "com.example.from-descriptor",
        displayName = "Example Plugin",
    )
}

private class EpWithThrowingDescriptor : StubExtensionPoint() {
    override fun getPluginDescriptor(): PluginDescriptor = throw IllegalStateException("desc boom")
}

private class EpWithPluginDescriptorNullName : StubExtensionPoint() {
    override fun getPluginDescriptor(): PluginDescriptor = ControllablePluginDescriptor.create(
        idString = "com.example.no-name",
        displayName = null,
    )
}

private class EpWithPluginDescriptorNullId : StubExtensionPoint() {
    override fun getPluginDescriptor(): PluginDescriptor =
        ControllablePluginDescriptor.createWithNullId()
}

private class EpWithEverything : StubExtensionPoint() {
    @JvmField val name: String = "com.example.everything"
    @JvmField val className: String = "com.example.IFace"
    fun getKind(): String = "INTERFACE"
    override fun isDynamic(): Boolean = true
    override fun size(): Int = 42
    override fun getPluginDescriptor(): PluginDescriptor = ControllablePluginDescriptor.create(
        idString = "com.example.everything-plugin",
        displayName = "Everything Plugin",
    )
}

private class EpWithThrowingSize : StubExtensionPoint() {
    override fun size(): Int = throw IllegalStateException("size boom")
}

/**
 * Builds a [PluginDescriptor] via [java.lang.reflect.Proxy] so we don't have to manually
 * implement the 40+ getters on the real interface. Only `getPluginId`, `getName`, and
 * `getPluginClassLoader` are wired; everything else returns a sensible default (null /
 * empty string / 0 / false). This is enough for the reflection paths the inspector exercises.
 */
private object ControllablePluginDescriptor {
    fun create(idString: String, displayName: String?): PluginDescriptor =
        build("getPluginId" to com.intellij.openapi.extensions.PluginId.getId(idString),
              "getName" to displayName)

    /** Variant where `getPluginId()` reflectively returns null — covers the `?: "unknown"` branch. */
    fun createWithNullId(): PluginDescriptor =
        build("getPluginId" to null, "getName" to null)

    private fun build(vararg routes: Pair<String, Any?>): PluginDescriptor {
        val table = routes.toMap()
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            if (method.name in table) return@InvocationHandler table[method.name]
            when (method.name) {
                "getPluginClassLoader" -> ClassLoader.getSystemClassLoader()
                "toString" -> "PluginDescriptor[stub]"
                "hashCode" -> 0
                "equals" -> false
                else -> defaultFor(method.returnType)
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            PluginDescriptor::class.java.classLoader,
            arrayOf(PluginDescriptor::class.java),
            handler,
        ) as PluginDescriptor
    }

    private fun defaultFor(t: Class<*>): Any? = when (t) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Void.TYPE -> null
        else -> null
    }
}

// ========================================================================================
// SECTION 16. Fixtures — isDynamic
// ========================================================================================

private class EpWithIsDynamic(private val value: Boolean) : StubExtensionPoint() {
    override fun isDynamic(): Boolean = value
}

private class EpWithIsDynamicThrowing : StubExtensionPoint() {
    override fun isDynamic(): Boolean = throw IllegalStateException("dynamic boom")
}

// ========================================================================================
// SECTION 17. Fixtures — synthetic ExtensionsArea
// ========================================================================================

/**
 * Stub base for ExtensionsArea with no-op implementations.
 */
private abstract class StubExtensionsArea : ExtensionsArea {
    override fun registerExtensionPoint(
        extensionPointName: String,
        extensionPointBeanClass: String,
        kind: ExtensionPoint.Kind,
        isDynamic: Boolean,
    ) {}
    override fun unregisterExtensionPoint(extensionPointName: String) {}
    override fun hasExtensionPoint(extensionPointName: String): Boolean = false
    override fun hasExtensionPoint(extensionPointName: ExtensionPointName<*>): Boolean = false
    override fun <T : Any> getExtensionPoint(extensionPointName: String): ExtensionPoint<T> =
        error("not implemented")
    override fun <T : Any> getExtensionPoint(extensionPointName: ExtensionPointName<T>): ExtensionPoint<T> =
        error("not implemented")
    override fun <T : Any> getExtensionPointIfRegistered(extensionPointName: String): ExtensionPoint<T>? = null
    override fun processExtensionPoints(consumer: (ExtensionPointImpl<*>) -> Unit) {}
    override val nameToPointMap: Map<String, ExtensionPointImpl<*>> = emptyMap()
}

private class AreaWithMapMethod(private val map: Map<String, Any>) : StubExtensionsArea() {
    fun getExtensionPoints(): Map<String, Any> = map
}

private class AreaWithCollectionMethod(private val coll: Collection<Any>) : StubExtensionsArea() {
    fun getExtensionPoints(): Collection<Any> = coll
}

private class AreaWithFieldOnly(@JvmField val extensionPoints: Map<String, Any>) : StubExtensionsArea()

private class AreaWithNothing : StubExtensionsArea()

private class AreaWithStringMethod(private val s: String) : StubExtensionsArea() {
    @Suppress("unused") fun getExtensionPoints(): String = s
}

// ========================================================================================
// SECTION 18. Fixtures — adapters for readAdditionalAttributes
// ========================================================================================

private class AdapterWithElementAttributesMap(attrs: Map<String, String>) {
    @JvmField val extensionElement: Any = ElementWithAttributesMap(attrs)
}

private class ElementWithAttributesMap(@JvmField val attributes: Map<String, String>)

private class AdapterWithElementGetAttributesMap(attrs: Map<String, String>) {
    @JvmField val extensionElement: Any = ElementWithGetAttributesMap(attrs)
}

private class ElementWithGetAttributesMap(private val attrs: Map<String, String>) {
    fun getAttributes(): Map<String, String> = attrs
}

private class AdapterWithElementGetAttributesList(attrs: List<Any?>) {
    @JvmField val extensionElement: Any = ElementWithGetAttributesList(attrs)
}

private class ElementWithGetAttributesList(private val attrs: List<Any?>) {
    fun getAttributes(): List<Any?> = attrs
}

private class FakeAttr(private val n: String, private val v: String) {
    fun getName(): String = n
    fun getValue(): String = v
}

private class AdapterWithInstance(@JvmField val extensionInstance: Any)

private class BeanWithAttributes {
    @JvmField val implementation: String = "com.example.Bean"
    @JvmField val order: Int = 12
}

private class AdapterWithBoth(elementAttrs: Map<String, String>, instance: Any) {
    @JvmField val extensionElement: Any = ElementWithAttributesMap(elementAttrs)
    @JvmField val extensionInstance: Any = instance
}

private class EmptyAdapter

private class AdapterWithThrowingElement {
    @JvmField val extensionElement: Any = ElementThatThrowsOnAttributes
}

private object ElementThatThrowsOnAttributes {
    @Suppress("unused")
    fun getAttributes(): Map<String, String> = throw IllegalStateException("attr boom")
}

private class FakeAttrNullable(private val n: String?, private val v: String?) {
    fun getName(): String? = n
    fun getValue(): String? = v
}

private class AdapterWithElementAttributesMapNullable(attrs: Map<String?, String?>) {
    @JvmField val extensionElement: Any = ElementWithAttributesMapNullable(attrs)
}

private class ElementWithAttributesMapNullable(@JvmField val attributes: Map<String?, String?>)

private class AdapterWithThrowingBoth {
    @JvmField val extensionElement: Any = ElementThatThrowsOnAttributes
    @JvmField val extensionInstance: Any = InstanceThatThrowsOnHarvest()
}

private class InstanceThatThrowsOnHarvest {
    // Define a field whose access throws — harvestBeanFields hits this; production catches it.
    @Suppress("unused")
    @JvmField val problematic: Any = object {
        override fun toString(): String = throw IllegalStateException("harvest boom")
    }
}
