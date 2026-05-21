package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Rectangle
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Tests for [ComponentSerializer.toInfo].
 *
 * All assertions run on the Swing EDT via [onEdt] because Swing components are not
 * thread-safe and `ComponentSerializer` is documented as "must be called on the EDT".
 *
 * The registry is a plain in-memory `HashMap`/`WeakHashMap` with no lifecycle, so we
 * just create a fresh one per test rather than sharing state.
 */
class ComponentSerializerTest {

    private fun freshRegistry() = ComponentRegistry()

    // ====================================================================================
    // SECTION 1. Text extraction
    // ====================================================================================

    @Test
    fun `serialises JButton text into ComponentInfo text`() {
        val registry = freshRegistry()
        val info = onEdt {
            val button = JButton("Click me")
            ComponentSerializer.toInfo(button, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertEquals("Click me", info.text)
    }

    @Test
    fun `serialises JLabel text into ComponentInfo text`() {
        val registry = freshRegistry()
        val info = onEdt {
            val label = JLabel("hello")
            ComponentSerializer.toInfo(label, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertEquals("hello", info.text)
    }

    @Test
    fun `serialises JTextField text into ComponentInfo text`() {
        val registry = freshRegistry()
        val info = onEdt {
            val field = JTextField("typed in")
            ComponentSerializer.toInfo(field, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertEquals("typed in", info.text)
    }

    @Test
    fun `truncates JTextComponent text past 500 chars with ellipsis`() {
        val registry = freshRegistry()
        val long = "a".repeat(600)
        val info = onEdt {
            val field = JTextField(long)
            ComponentSerializer.toInfo(field, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        val text = info.text
        assertNotNull("text must not be null after truncation", text)
        assertEquals(501, text!!.length)
        assertTrue("must end with ellipsis", text.endsWith("…"))
    }

    @Test
    fun `text is null for non-text component`() {
        val registry = freshRegistry()
        val info = onEdt {
            val panel = JPanel()
            ComponentSerializer.toInfo(panel, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertNull(info.text)
    }

    // ====================================================================================
    // SECTION 2. Bounds
    // ====================================================================================

    @Test
    fun `bounds reflect setBounds`() {
        val registry = freshRegistry()
        val info = onEdt {
            val button = JButton("x")
            button.setBounds(10, 20, 30, 40)
            ComponentSerializer.toInfo(button, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertEquals(Bounds(10, 20, 30, 40), info.bounds)
    }

    @Test
    fun `bounds default to zero on exception`() {
        val registry = freshRegistry()
        val info = onEdt {
            // Anonymous JComponent that throws when its bounds are read.
            val broken = object : JComponent() {
                override fun getBounds(): Rectangle = throw RuntimeException("boom")
            }
            ComponentSerializer.toInfo(broken, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertEquals(Bounds(0, 0, 0, 0), info.bounds)
    }

    // ====================================================================================
    // SECTION 3. Accessibility
    // ====================================================================================

    @Test
    fun `accessibleName picked up from AccessibleContext`() {
        val registry = freshRegistry()
        val info = onEdt {
            val label = JLabel("hi").apply { accessibleContext.accessibleName = "Greeter" }
            ComponentSerializer.toInfo(label, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertEquals("Greeter", info.accessibleName)
    }

    @Test
    fun `accessibleRole is non-null for accessible components`() {
        val registry = freshRegistry()
        val info = onEdt {
            val button = JButton("x")
            ComponentSerializer.toInfo(button, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        val role = info.accessibleRole
        assertNotNull("accessibleRole must be set for JButton", role)
        assertTrue("accessibleRole must be a non-empty string", role!!.isNotEmpty())
    }

    @Test
    fun `tooltipText extracted from JComponent`() {
        val registry = freshRegistry()
        val info = onEdt {
            val button = JButton("x").apply { toolTipText = "Click here" }
            ComponentSerializer.toInfo(button, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertEquals("Click here", info.toolTipText)
    }

    // ====================================================================================
    // SECTION 4. Children
    // ====================================================================================

    @Test
    fun `children list contains direct child ids`() {
        val registry = freshRegistry()
        val result = onEdt {
            val panel = JPanel()
            val b1 = JButton("one")
            val b2 = JButton("two")
            val b3 = JButton("three")
            panel.add(b1)
            panel.add(b2)
            panel.add(b3)
            val id1 = registry.register(b1)
            val id2 = registry.register(b2)
            val id3 = registry.register(b3)
            val info = ComponentSerializer.toInfo(panel, registry, includeProperties = false, truncatePropertyValueAt = 200)
            Triple(info, listOf(id1, id2, id3), Unit)
        }
        val info = result.first
        val expectedIds = result.second
        assertEquals(3, info.children.size)
        assertEquals(expectedIds, info.children)
    }

    // ====================================================================================
    // SECTION 5. Properties
    // ====================================================================================

    @Test
    fun `properties empty when includeProperties is false`() {
        val registry = freshRegistry()
        val info = onEdt {
            val panel = JPanel().apply { putClientProperty("place", "north") }
            ComponentSerializer.toInfo(panel, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertTrue("properties must be empty when includeProperties=false", info.properties.isEmpty())
    }

    @Test
    fun `properties collected when includeProperties is true`() {
        val registry = freshRegistry()
        val info = onEdt {
            val panel = JPanel().apply { putClientProperty("place", "north") }
            ComponentSerializer.toInfo(panel, registry, includeProperties = true, truncatePropertyValueAt = 200)
        }
        val place = info.properties.firstOrNull { it.name == "place" }
        assertNotNull("expected a property named 'place'", place)
        assertEquals("north", place!!.value)
    }

    @Test
    fun `client properties truncated at limit`() {
        val registry = freshRegistry()
        val info = onEdt {
            val panel = JPanel().apply { putClientProperty("place", "x".repeat(1000)) }
            ComponentSerializer.toInfo(panel, registry, includeProperties = true, truncatePropertyValueAt = 50)
        }
        val place = info.properties.firstOrNull { it.name == "place" }
        assertNotNull(place)
        val value = place!!.value
        assertEquals(51, value.length)
        assertTrue("truncated value must end with ellipsis", value.endsWith("…"))
    }

    @Test
    fun `action property collected for ActionButton-like class`() {
        val registry = freshRegistry()
        val info = onEdt {
            // FakeActionButton's class name contains the literal substring "ActionButton",
            // so ComponentSerializer takes the reflection path that calls getAction().
            // JButton.getAction() returns the Action set via setAction(...).
            val button = FakeActionButton()
            button.action = MyTestAction()
            ComponentSerializer.toInfo(button, registry, includeProperties = true, truncatePropertyValueAt = 200)
        }
        val action = info.properties.firstOrNull { it.name == "action" }
        assertNotNull("expected an 'action' property for an ActionButton-like class", action)
        assertEquals(MyTestAction::class.java.name, action!!.value)
    }

    // ====================================================================================
    // SECTION 6. Corner cases
    //
    // The cases below pin down the "small null branch" paths that the happy-path tests
    // above can't reach: non-JComponent inputs, Accessible without an accessibleRole, a
    // JTextComponent that throws on getText, an `*ActionButton` class without a getAction
    // method, and the truncate-zero-limit short-circuit.
    // ====================================================================================

    @Test
    fun `toolTipText is null for non-JComponent inputs`() {
        // A bare AWT Component is not a JComponent, so the `(component as? JComponent)`
        // cast yields null and toolTipText must not be probed.
        val registry = freshRegistry()
        val info = onEdt {
            val plain = NonJComponent()
            ComponentSerializer.toInfo(plain, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertNull("non-JComponent must have null toolTipText", info.toolTipText)
    }

    @Test
    fun `text is null for non-AbstractButton non-JLabel non-JTextComponent components`() {
        // Drives the `else -> null` arm of `extractText`. A plain AWT Component is the
        // simplest fixture that hits none of the typed branches.
        val registry = freshRegistry()
        val info = onEdt {
            val plain = NonJComponent()
            ComponentSerializer.toInfo(plain, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertNull("plain component must have null text", info.text)
    }

    @Test
    fun `accessibleRole is null when AccessibleContext returns null role`() {
        // Exercises the second `?.` link in `accessible?.accessibleRole?.toString()`.
        // We use a JComponent subclass whose AccessibleContext deliberately answers null
        // for getAccessibleRole.
        val registry = freshRegistry()
        val info = onEdt {
            val c = NoRoleAccessibleJComponent()
            ComponentSerializer.toInfo(c, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertNull("accessibleRole must be null when AccessibleContext returns null role", info.accessibleRole)
    }

    @Test
    fun `children list is empty for non-Container components`() {
        // collectChildIds short-circuits on non-Container; the only way to reach that arm
        // is to pass an AWT Component that is not a Container.
        val registry = freshRegistry()
        val info = onEdt {
            val plain = NonJComponent()
            ComponentSerializer.toInfo(plain, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertTrue("non-Container must report no children", info.children.isEmpty())
    }

    @Test
    fun `text is null when JTextComponent_getText throws`() {
        // Defensive catch in extractText: a JTextComponent whose getText() throws must
        // surface as null text, not propagate the exception.
        val registry = freshRegistry()
        val info = onEdt {
            val broken = object : JTextField() {
                override fun getText(): String = throw RuntimeException("boom")
            }
            ComponentSerializer.toInfo(broken, registry, includeProperties = false, truncatePropertyValueAt = 200)
        }
        assertNull("broken JTextComponent must surface null text", info.text)
    }

    @Test
    fun `properties empty for non-JComponent inputs even when includeProperties is true`() {
        // collectProperties first checks `component is JComponent`. A bare Component must
        // skip that block entirely; the only remaining branch is the ActionButton-named
        // reflection lookup which also yields nothing for an arbitrary class name.
        val registry = freshRegistry()
        val info = onEdt {
            val plain = NonJComponent()
            ComponentSerializer.toInfo(plain, registry, includeProperties = true, truncatePropertyValueAt = 200)
        }
        assertTrue("non-JComponent must have no collected properties", info.properties.isEmpty())
    }

    @Test
    fun `action property absent for ActionButton-like class without getAction method`() {
        // Class name contains "ActionButton" so the reflection probe runs, but no
        // `getAction()` method exists, so the result is silently empty rather than
        // throwing NoSuchMethodException.
        val registry = freshRegistry()
        val info = onEdt {
            val odd = BareActionButton()
            ComponentSerializer.toInfo(odd, registry, includeProperties = true, truncatePropertyValueAt = 200)
        }
        assertNull(
            "no 'action' property must be reported when getAction is missing",
            info.properties.firstOrNull { it.name == "action" },
        )
    }

    @Test
    fun `client property string is returned untruncated when limit is zero or negative`() {
        // truncate short-circuits when limit <= 0, returning the original string verbatim.
        // The shortest path to that branch is a long client property with truncateAt=0.
        val registry = freshRegistry()
        val long = "x".repeat(1000)
        val infoZero = onEdt {
            val panel = JPanel().apply { putClientProperty("place", long) }
            ComponentSerializer.toInfo(panel, registry, includeProperties = true, truncatePropertyValueAt = 0)
        }
        val placeZero = infoZero.properties.firstOrNull { it.name == "place" }
        assertNotNull(placeZero)
        assertEquals("limit=0 must disable truncation", 1000, placeZero!!.value.length)
        assertFalse("limit=0 must not append ellipsis", placeZero.value.endsWith("…"))

        val infoNeg = onEdt {
            val panel = JPanel().apply { putClientProperty("place", long) }
            ComponentSerializer.toInfo(panel, registry, includeProperties = true, truncatePropertyValueAt = -5)
        }
        val placeNeg = infoNeg.properties.firstOrNull { it.name == "place" }
        assertNotNull(placeNeg)
        assertEquals("negative limit must disable truncation", 1000, placeNeg!!.value.length)
    }

    // -- helper types ----------------------------------------------------------------------

    /**
     * Name is deliberately chosen so the FQN contains "ActionButton" — that's the substring
     * `ComponentSerializer.collectProperties` looks for before invoking `getAction()`.
     */
    private class FakeActionButton : JButton()

    private class MyTestAction : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) { /* no-op */ }
    }

    /**
     * A non-Container, non-JComponent Component subclass. Reachable AWT base class:
     * the bare [Component] needs a Toolkit peer to render, but `ComponentSerializer`
     * only reads its bounds / name / accessibility fields, all of which are populated
     * by the AWT Component constructor.
     */
    private class NonJComponent : Component()

    /**
     * Class name contains "ActionButton" so [ComponentSerializer.collectProperties] takes
     * the reflection path — but `getAction()` is not declared, so `methods.firstOrNull`
     * returns null. The class extends [JPanel] (no inherited `getAction()`) to keep the
     * shape predictable.
     */
    private class BareActionButton : JPanel()

    /**
     * A JComponent whose AccessibleContext reports null for `getAccessibleRole`. Drives
     * the null-arm of `accessible?.accessibleRole?.toString()`.
     */
    private class NoRoleAccessibleJComponent : JComponent() {
        override fun getAccessibleContext(): javax.accessibility.AccessibleContext {
            return object : javax.accessibility.AccessibleContext() {
                override fun getAccessibleRole(): javax.accessibility.AccessibleRole? = null
                override fun getAccessibleStateSet(): javax.accessibility.AccessibleStateSet =
                    javax.accessibility.AccessibleStateSet()

                override fun getAccessibleIndexInParent(): Int = -1
                override fun getAccessibleChildrenCount(): Int = 0
                override fun getAccessibleChild(i: Int): javax.accessibility.Accessible? = null
                override fun getLocale(): java.util.Locale = java.util.Locale.getDefault()
            }
        }
    }
}
