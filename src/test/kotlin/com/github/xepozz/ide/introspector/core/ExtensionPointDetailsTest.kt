package com.github.xepozz.ide.introspector.core

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Property
import com.intellij.openapi.extensions.RequiredElement
import com.intellij.util.xmlb.annotations.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the bean-schema and interface-method harvesters that back
 * `arch.get_extension_point_details`. The harvesters are `internal` on
 * [ExtensionPointInspector] so the test exercises them with synthetic fixture classes
 * — no live IDE needed. The platform test under `core/platform/` covers the live
 * `getDetails` entry point end-to-end.
 */
class ExtensionPointDetailsTest {

    // -- bean schema -----------------------------------------------------------------------

    @Test
    fun `harvestBeanSchema picks up @Attribute with explicit name and @RequiredElement`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(SimpleBean::class.java, 200)
        val id = schema.fields.firstOrNull { it.name == "id" }
            ?: error("Expected 'id' field in schema, got ${schema.fields.map { it.name }}")
        assertEquals("id", id.xmlAttributeName)
        assertNull(id.xmlTagName)
        assertTrue("'id' must be required", id.required)
        assertFalse("'id' must not be flagged deprecated", id.deprecated)
        assertEquals("java.lang.String", id.type)
    }

    @Test
    fun `harvestBeanSchema renames via @Attribute(value=…)`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(RenamedBean::class.java, 200)
        val anchor = schema.fields.firstOrNull { it.name == "myAnchor" }
            ?: error("Expected 'myAnchor' field, got ${schema.fields.map { it.name }}")
        // @Attribute("anchor") on field myAnchor → XML name "anchor", java field name preserved.
        assertEquals("anchor", anchor.xmlAttributeName)
    }

    @Test
    fun `harvestBeanSchema includes public unannotated fields with field name as XML attribute`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(PublicBean::class.java, 200)
        val plain = schema.fields.firstOrNull { it.name == "plainPublic" }
            ?: error("Expected 'plainPublic' field, got ${schema.fields.map { it.name }}")
        assertEquals("plainPublic", plain.xmlAttributeName)
        assertFalse(plain.required)
    }

    @Test
    fun `harvestBeanSchema skips private unannotated fields and static fields`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(PublicBean::class.java, 200)
        val names = schema.fields.map { it.name }
        assertFalse("private unannotated 'secret' must be skipped; got $names", names.contains("secret"))
        assertFalse("static 'CONST' must be skipped; got $names", names.contains("CONST"))
    }

    @Test
    fun `harvestBeanSchema renders @Tag as nested-element entry`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(TaggedBean::class.java, 200)
        val nested = schema.fields.firstOrNull { it.name == "nested" }
            ?: error("Expected 'nested' field, got ${schema.fields.map { it.name }}")
        assertNull("@Tag fields have no attribute name", nested.xmlAttributeName)
        assertEquals("nestedTag", nested.xmlTagName)
    }

    @Test
    fun `harvestBeanSchema marks @Deprecated fields with deprecated=true`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(DeprecatedBean::class.java, 200)
        val old = schema.fields.firstOrNull { it.name == "oldField" }
            ?: error("Expected 'oldField' in schema, got ${schema.fields.map { it.name }}")
        assertTrue("oldField must be deprecated", old.deprecated)
    }

    @Test
    fun `harvestBeanSchema truncates at maxFields and reports truncated=true`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(FiveFieldBean::class.java, 2)
        assertEquals(2, schema.fields.size)
        assertTrue("truncated must be true once maxFields cap hits", schema.truncated)
    }

    @Test
    fun `harvestBeanSchema does not flag truncated when under cap`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(FiveFieldBean::class.java, 200)
        assertFalse("truncated must be false when result fits", schema.truncated)
    }

    @Test
    fun `harvestBeanSchema walks the superclass hierarchy`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(ChildBean::class.java, 200)
        val names = schema.fields.map { it.name }.toSet()
        assertTrue("expected inherited 'parentField' in schema, got $names", names.contains("parentField"))
        assertTrue("expected own 'childField' in schema, got $names", names.contains("childField"))
    }

    @Test
    fun `harvestBeanSchema className equals the input FQN`() {
        val schema = ExtensionPointInspector.harvestBeanSchema(SimpleBean::class.java, 200)
        assertEquals(SimpleBean::class.java.name, schema.className)
    }

    // -- interface methods -----------------------------------------------------------------

    @Test
    fun `harvestInterfaceMethods lists abstract methods of a SAM-style interface`() {
        val methods = ExtensionPointInspector.harvestInterfaceMethods(SampleProvider::class.java, 200)
        assertFalse("Expected at least one abstract method on SampleProvider", methods.isEmpty())
        val names = methods.map { it.name }.toSet()
        assertTrue("expected 'provide' in $names", names.contains("provide"))
    }

    @Test
    fun `harvestInterfaceMethods formats signature as paren-list colon-return`() {
        val methods = ExtensionPointInspector.harvestInterfaceMethods(SampleProvider::class.java, 200)
        val provide = methods.firstOrNull { it.name == "provide" }
            ?: error("missing 'provide' method")
        assertEquals("(String, int): String", provide.signature)
        assertEquals("String", provide.returnType)
    }

    @Test
    fun `harvestInterfaceMethods truncates at maxFields`() {
        val methods = ExtensionPointInspector.harvestInterfaceMethods(WideProvider::class.java, 2)
        assertEquals(2, methods.size)
    }

    @Test
    fun `harvestInterfaceMethods skips java-lang-Object overrides`() {
        // Object provides toString/equals/hashCode as concrete; abstract filter drops them.
        val methods = ExtensionPointInspector.harvestInterfaceMethods(SampleProvider::class.java, 200)
        val names = methods.map { it.name }
        assertFalse("toString must not appear (concrete on Object)", names.contains("toString"))
    }
}

// ============================================================================================
// Fixtures — synthetic bean classes feeding `harvestBeanSchema`. Annotations are resolved
// reflectively by FQN, so any class on the test classpath that has the xmlb annotations
// applied is enough.
// ============================================================================================

private class SimpleBean {
    @Attribute("id")
    @RequiredElement
    @JvmField
    var id: String = ""
}

private class RenamedBean {
    @Attribute("anchor")
    @JvmField
    var myAnchor: String = ""
}

private class PublicBean {
    @JvmField var plainPublic: String = "abc"

    @Suppress("unused")
    private val secret: String = "hidden"

    companion object {
        @Suppress("unused", "ConstPropertyName")
        const val CONST: String = "const-val"
    }
}

private class TaggedBean {
    @Tag("nestedTag")
    @JvmField
    var nested: String = ""
}

private class DeprecatedBean {
    @Deprecated("legacy")
    @JvmField
    var oldField: String = ""
}

private class FiveFieldBean {
    @JvmField var a: String = ""
    @JvmField var b: String = ""
    @JvmField var c: String = ""
    @JvmField var d: String = ""
    @JvmField var e: String = ""
}

private open class ParentBean {
    @JvmField var parentField: String = ""
}

private class ChildBean : ParentBean() {
    @JvmField var childField: String = ""
}

// Interface fixtures.
private interface SampleProvider {
    fun provide(name: String, count: Int): String
    fun reset()
}

private interface WideProvider {
    fun a()
    fun b()
    fun c()
    fun d()
    fun e()
}

// Stashed for future @Property(style=TAG) coverage — keeps the import reachable.
@Suppress("unused")
private val keepPropertyImportReachable: Class<*> = Property::class.java
