package com.github.xepozz.introspectorplugin.core

import com.github.xepozz.introspectorplugin.model.Bounds
import com.github.xepozz.introspectorplugin.model.ComponentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Characterisation + spec tests for [XPathMatcher].
 *
 * The fixture below is a small synthetic Swing-like tree:
 *
 * ```
 * frame   (JFrame)
 * ├── panel   (JPanel, accessibleName="Main")
 * │   ├── btn1    (JButton, text="OK", name="okButton")
 * │   ├── btn2    (JButton, text="Cancel")
 * │   └── label   (JBLabel, text="Hello")
 * └── btn3    (JButton, text="Detail")
 * ```
 *
 * The XPath subset under test mirrors `intellij-ui-test-robot` — see the KDoc
 * on [XPathMatcher] for the supported axes / predicates.
 *
 * Seeds (`rootIds`) are the root nodes themselves, not a virtual document root.
 * Consequence:
 *   - `/JButton`  → JButtons that are *direct children of the root frame*  → [btn3]
 *   - `//JButton` → JButtons anywhere in the tree (including the root)     → [btn1, btn2, btn3]
 *   - `//JFrame`  → matches the root via descendant-or-self                → [frame]
 */
class XPathMatcherTest {

    private val bounds = Bounds(0, 0, 100, 100)

    private fun node(
        id: String,
        className: String,
        text: String? = null,
        accessibleName: String? = null,
        name: String? = null,
        toolTipText: String? = null,
        children: List<String> = emptyList(),
    ) = ComponentInfo(
        id = id,
        className = className,
        name = name,
        accessibleName = accessibleName,
        bounds = bounds,
        visible = true,
        enabled = true,
        text = text,
        toolTipText = toolTipText,
        children = children,
    )

    private val nodes: Map<String, ComponentInfo> = listOf(
        node("frame", "javax.swing.JFrame", children = listOf("panel", "btn3")),
        node(
            "panel", "javax.swing.JPanel", accessibleName = "Main",
            children = listOf("btn1", "btn2", "label"),
        ),
        node("btn1", "javax.swing.JButton", text = "OK", name = "okButton"),
        node("btn2", "javax.swing.JButton", text = "Cancel"),
        node("label", "com.intellij.ui.components.JBLabel", text = "Hello"),
        node("btn3", "javax.swing.JButton", text = "Detail"),
    ).associateBy { it.id }

    private val roots = listOf("frame")

    private fun ids(xpath: String, limit: Int = 100): List<String> =
        XPathMatcher(nodes, roots).query(xpath, limit).map { it.id }

    // ---------- Axes ----------

    @Test
    fun `absolute child step looks among root's children`() {
        assertEquals(listOf("panel"), ids("/JPanel"))
        assertEquals(listOf("btn3"), ids("/JButton"))
    }

    @Test
    fun `descendant-or-self includes the root`() {
        assertEquals(listOf("frame"), ids("//JFrame"))
    }

    @Test
    fun `descendant-or-self finds nodes at any depth`() {
        assertEquals(listOf("btn1", "btn2", "btn3"), ids("//JButton"))
    }

    @Test
    fun `bareword is treated as descendant-or-self`() {
        // No leading `/` → parser prepends `//`.
        assertEquals(listOf("btn1", "btn2", "btn3"), ids("JButton"))
    }

    @Test
    fun `star wildcard matches any element`() {
        // Document order: frame, then panel and its subtree, then btn3.
        assertEquals(
            listOf("frame", "panel", "btn1", "btn2", "label", "btn3"),
            ids("//*"),
        )
    }

    @Test
    fun `div is wildcard alias used by remote-robot`() {
        assertEquals(
            listOf("frame", "panel", "btn1", "btn2", "label", "btn3"),
            ids("//div"),
        )
    }

    @Test
    fun `child after child descends one level at a time`() {
        assertEquals(listOf("panel"), ids("/JPanel"))
        assertEquals(listOf("btn1", "btn2"), ids("/JPanel//JButton"))
    }

    @Test
    fun `mixed child + descendant`() {
        assertEquals(listOf("btn1", "btn2"), ids("/JPanel/JButton"))
    }

    // ---------- Name matching ----------

    @Test
    fun `localName matches simple class name`() {
        assertEquals(listOf("btn1", "btn2", "btn3"), ids("//JButton"))
    }

    @Test
    fun `localName also matches fully qualified class name`() {
        assertEquals(listOf("btn1", "btn2", "btn3"), ids("//javax.swing.JButton"))
    }

    @Test
    fun `name match is case-sensitive`() {
        // Intentional: Swing class names are mixed case; lowercased queries do NOT match.
        // If this ever changes, document the compat impact with intellij-ui-test-robot.
        assertEquals(emptyList<String>(), ids("//jbutton"))
    }

    // ---------- Attribute predicates ----------

    @Test
    fun `class predicate uses simple name`() {
        assertEquals(listOf("label"), ids("//*[@class='JBLabel']"))
    }

    @Test
    fun `fqClass predicate uses fully qualified name`() {
        assertEquals(
            listOf("label"),
            ids("//*[@fqClass='com.intellij.ui.components.JBLabel']"),
        )
    }

    @Test
    fun `name predicate matches Swing component name property`() {
        assertEquals(listOf("btn1"), ids("//*[@name='okButton']"))
    }

    @Test
    fun `accessibleName predicate`() {
        assertEquals(listOf("panel"), ids("//*[@accessibleName='Main']"))
    }

    @Test
    fun `text predicate`() {
        assertEquals(listOf("btn1"), ids("//*[@text='OK']"))
    }

    @Test
    fun `double-quoted predicate value`() {
        assertEquals(listOf("btn1"), ids("//*[@text=\"OK\"]"))
    }

    @Test
    fun `combined predicates with and`() {
        assertEquals(
            listOf("btn1"),
            ids("//JButton[@text='OK' and @name='okButton']"),
        )
    }

    @Test
    fun `combined predicates short-circuit on mismatch`() {
        assertEquals(
            emptyList<String>(),
            ids("//JButton[@text='OK' and @name='wrong']"),
        )
    }

    // ---------- Positional predicate ----------

    @Test
    fun `positional predicate respects document order`() {
        // Document order of JButtons: btn1 (in panel), btn2 (in panel), btn3 (sibling of panel).
        // This used to be broken when the matcher walked the tree BFS instead of DFS.
        assertEquals(listOf("btn1"), ids("//JButton[1]"))
        assertEquals(listOf("btn2"), ids("//JButton[2]"))
        assertEquals(listOf("btn3"), ids("//JButton[3]"))
    }

    @Test
    fun `positional predicate out of range yields empty`() {
        assertEquals(emptyList<String>(), ids("//JButton[99]"))
    }

    @Test
    fun `positional predicate applies after attribute predicates`() {
        // [@text='OK'][1] — first JButton with text='OK' is btn1.
        // (XPath 1.0 semantics: positional applies to the already-filtered set.)
        assertEquals(listOf("btn1"), ids("//JButton[@text='OK']"))
    }

    // ---------- Self axis ----------

    @Test
    fun `dot is self axis`() {
        // From the root seed, `.` (or `/.` or `//.`) selects the seed itself.
        assertEquals(listOf("frame"), ids("/."))
    }

    @Test
    fun `dot keeps its predicates`() {
        // Regression: `.[predicate]` used to silently drop the predicate, so this matched
        // the root unconditionally instead of filtering it out.
        assertEquals(
            emptyList<String>(),
            ids("/.[@class='JBLabel']"),
        )
    }

    @Test
    fun `dot with matching predicate keeps the node`() {
        assertEquals(listOf("frame"), ids("/.[@class='JFrame']"))
    }

    // ---------- Limit ----------

    @Test
    fun `limit caps result size`() {
        assertEquals(listOf("btn1", "btn2"), ids("//JButton", limit = 2))
    }

    @Test
    fun `limit zero yields empty`() {
        assertEquals(emptyList<String>(), ids("//JButton", limit = 0))
    }

    // ---------- Errors / diagnostics ----------

    @Test
    fun `unknown attribute surfaces an actionable error`() {
        try {
            ids("//*[@unknown='x']")
            fail("expected IllegalArgumentException for unknown attribute")
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            assertTrue("error must name the bad attribute: $msg", msg.contains("@unknown"))
            assertTrue(
                "error must list supported attributes: $msg",
                msg.contains("@class") && msg.contains("@text"),
            )
        }
    }

    @Test
    fun `unbalanced predicate throws`() {
        try {
            ids("//*[@class='x'")
            fail("expected IllegalArgumentException for unbalanced bracket")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }
}
