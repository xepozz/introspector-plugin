package com.github.xepozz.ide.introspector.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ExtensionMetadata].
 *
 * Two goals:
 *   1. **Spec coverage**: every branch of `pickEffectiveClass` and `harvestBeanFields`
 *      has an explicit assertion.
 *   2. **Documentation by example**: each test reads as a realistic mini-scenario — the
 *      attribute priority order and the bean-harvest filter rules are easier to remember
 *      from a test name than from a comment in the source.
 *
 * ## Fixture: minimal bean classes
 *
 * The bean fixtures below are intentionally JvmField-flavoured because that's what the
 * production code targets — IntelliJ's `@Attribute` beans declare their attribute
 * fields as plain JVM fields, and `harvestBeanFields` walks `declaredFields`.
 *
 * ```
 * BeanWithStringField  → one nullable String "implementation"
 * BeanWithMixed        → String + Number + Boolean + Char + a non-primitive (skipped)
 * Parent / Child       → superclass field visibility
 * WithStatic           → static + instance fields side by side
 * WithPrivate          → private field accessed via setAccessible
 * BeanWithNull         → null-valued field is skipped silently
 * ```
 *
 * ## How to read these tests
 *
 * Most cases run `harvestBeanFields(bean, dest)` then assert against the resulting map.
 * For `pickEffectiveClass` the body is a one-liner against a small `mapOf(...)`.
 */
class ExtensionMetadataTest {

    // ====================================================================================
    // SECTION 1. pickEffectiveClass
    // ====================================================================================

    @Test
    fun `returns implClass when no attributes match`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf("unrelated" to "x"),
        )
        assertEquals("com.example.Default", result)
    }

    @Test
    fun `prefers implementation attribute over implClass`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf("implementation" to "com.example.Real"),
        )
        assertEquals("com.example.Real", result)
    }

    @Test
    fun `prefers factoryClass when implementation is missing`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf("factoryClass" to "com.example.Factory"),
        )
        assertEquals("com.example.Factory", result)
    }

    @Test
    fun `prefers instance when implementation and factoryClass missing`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf("instance" to "com.example.Instance"),
        )
        assertEquals("com.example.Instance", result)
    }

    @Test
    fun `respects priority order serviceImplementation before serviceInterface`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf(
                "serviceInterface" to "com.example.Interface",
                "serviceImplementation" to "com.example.Impl",
            ),
        )
        assertEquals("com.example.Impl", result)
    }

    @Test
    fun `falls back to serviceInterface when serviceImplementation absent`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf("serviceInterface" to "com.example.Interface"),
        )
        assertEquals("com.example.Interface", result)
    }

    @Test
    fun `skips blank values`() {
        // Empty and whitespace-only attribute values must not win; we fall through.
        val empty = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf("implementation" to ""),
        )
        assertEquals("com.example.Default", empty)

        val whitespace = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf("implementation" to "   "),
        )
        assertEquals("com.example.Default", whitespace)

        // Blank earlier-priority attribute must allow a non-blank later one to win.
        val chain = ExtensionMetadata.pickEffectiveClass(
            implClass = "com.example.Default",
            attributes = mapOf(
                "implementation" to "",
                "factoryClass" to "com.example.Factory",
            ),
        )
        assertEquals("com.example.Factory", chain)
    }

    @Test
    fun `returns null when implClass null and no attributes`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = null,
            attributes = emptyMap(),
        )
        assertNull(result)
    }

    @Test
    fun `falls back to class attribute as last resort`() {
        val result = ExtensionMetadata.pickEffectiveClass(
            implClass = null,
            attributes = mapOf("class" to "com.example.LastResort"),
        )
        assertEquals("com.example.LastResort", result)
    }

    // ====================================================================================
    // SECTION 2. harvestBeanFields
    // ====================================================================================

    @Test
    fun `harvests a single string field`() {
        val bean = BeanWithStringField()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        assertEquals("com.example.Foo", into["implementation"])
    }

    @Test
    fun `harvests String Number Boolean and Char fields`() {
        val bean = BeanWithMixed()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        assertEquals("tool", into["name"])
        assertEquals("5", into["order"])
        assertEquals("true", into["enabled"])
        assertEquals("x", into["ch"])
    }

    @Test
    fun `skips non-primitive complex types`() {
        val bean = BeanWithMixed()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        // `nested` is a Map — not in the String/Number/Boolean/Char allow-list.
        assertFalse("nested map field must be skipped: $into", into.containsKey("nested"))
    }

    @Test
    fun `walks superclass fields too`() {
        val bean = Child()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        assertEquals("c", into["childField"])
        assertEquals("p", into["parentField"])
    }

    @Test
    fun `skips static fields`() {
        val bean = WithStatic()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        assertEquals("y", into["instanceField"])
        assertFalse("static field must be skipped: $into", into.containsKey("staticField"))
    }

    @Test
    fun `does not overwrite a key already present in the target map`() {
        // Production use case: XML attributes harvested first take priority over bean
        // properties. We seed the map and verify the helper respects what's already there.
        val bean = BeanWithStringField()
        val into = mutableMapOf("implementation" to "preexisting")

        ExtensionMetadata.harvestBeanFields(bean, into)

        assertEquals("preexisting", into["implementation"])
    }

    @Test
    fun `skips null field values`() {
        val bean = BeanWithNull()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        assertFalse("null-valued field must be skipped: $into", into.containsKey("nullable"))
        // Sanity check — non-null sibling is harvested so we know the walk ran.
        assertEquals("present", into["nonNull"])
    }

    @Test
    fun `reads private fields via setAccessible`() {
        val bean = WithPrivate()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        // The helper sets isAccessible = true, so even Kotlin private fields are reachable.
        assertEquals("shh", into["secret"])
    }

    @Test
    fun `does not crash on empty bean`() {
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(EmptyBean(), into)

        assertTrue("empty bean must produce empty result: $into", into.isEmpty())
    }

    // ====================================================================================
    // SECTION 3. harvestBeanFields — corner cases for defensive branches
    //
    // The cases below target branches the happy-path bean fixtures don't reach: synthetic
    // fields (captured by anonymous Kotlin objects), and the `try/catch` around
    // `f.get(instance)` for fields that throw on access.
    // ====================================================================================

    @Test
    fun `skips synthetic fields generated by the Kotlin compiler`() {
        // A capturing anonymous object compiles to a class with a synthetic field for the
        // captured local. `harvestBeanFields` must filter it out — even though its value
        // is a non-null String, we don't want compiler-generated names leaking into the
        // bean dump.
        val captured = "captured-value"
        val bean: Any = object {
            @JvmField val realField: String = "kept"

            @Suppress("unused")
            fun touch(): String = captured  // forces the capture, creating a synthetic field
        }
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        assertEquals("kept", into["realField"])
        // The synthetic field's name is compiler-defined (e.g. `$captured`) so we don't
        // pin it; instead assert that no value in `into` equals the captured payload.
        assertFalse(
            "synthetic capture field must be skipped: $into",
            into.values.any { it == "captured-value" },
        )
    }

    @Test
    fun `swallows exceptions thrown during field access`() {
        // The defensive `catch (_: Throwable) {}` around the field read keeps the walk
        // alive when a value's `toString` throws. We declare a [Number] subclass that
        // crashes inside toString — Numbers are in the harvest allow-list, so the helper
        // ends up calling `v.toString()` on it and falls into the catch.
        val bean = BeanWithBrokenAccess()
        val into = mutableMapOf<String, String>()

        ExtensionMetadata.harvestBeanFields(bean, into)

        // Sibling still harvested — proves the walk continued past the throwing field.
        assertEquals("ok", into["sibling"])
        // The broken field never makes it into the map (catch swallowed the exception).
        assertFalse("broken field must be skipped: $into", into.containsKey("broken"))
    }
}

private class BrokenNumber : Number() {
    override fun toByte(): Byte = throw RuntimeException("boom")
    override fun toDouble(): Double = throw RuntimeException("boom")
    override fun toFloat(): Float = throw RuntimeException("boom")
    override fun toInt(): Int = throw RuntimeException("boom")
    override fun toLong(): Long = throw RuntimeException("boom")
    override fun toShort(): Short = throw RuntimeException("boom")
    override fun toString(): String = throw RuntimeException("boom")
}

private class BeanWithBrokenAccess {
    @JvmField val sibling: String = "ok"
    @JvmField val broken: Number = BrokenNumber()
}

// ====================================================================================
// Fixtures
// ====================================================================================

private class BeanWithStringField {
    @JvmField var implementation: String? = "com.example.Foo"
}

private class BeanWithMixed {
    @JvmField val name: String = "tool"
    @JvmField val order: Int = 5
    @JvmField val enabled: Boolean = true
    @JvmField val ch: Char = 'x'
    @JvmField val nested: Any = mapOf("k" to "v")  // should be skipped (not primitive-ish)
}

private open class Parent { @JvmField val parentField: String = "p" }
private class Child : Parent() { @JvmField val childField: String = "c" }

private class WithStatic {
    companion object { @JvmStatic var staticField: String = "x" }
    @JvmField val instanceField: String = "y"
}

private class WithPrivate { private val secret: String = "shh" }

private class BeanWithNull {
    @JvmField val nullable: String? = null
    @JvmField val nonNull: String = "present"
}

private class EmptyBean
