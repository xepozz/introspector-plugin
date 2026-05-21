package com.github.xepozz.ide.introspector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel

/**
 * Tests for [ComponentTreeWalker.walk] and [ComponentTreeWalker.ancestors].
 *
 * The walker is the engine behind every `ui.*` MCP tool: a single bug here mis-shapes
 * every snapshot the client sees. So this suite focuses on the contract:
 *
 *   - BFS order (parent before children, siblings left-to-right).
 *   - Honest depth reporting.
 *   - `maxDepth` cut-off is inclusive (depth 0 = root only).
 *   - `includeInvisible` correctly gates non-visible subtrees.
 *   - Visitor's `false` return prunes only that branch — siblings continue.
 *   - `Window.ownedWindows` are followed (this is how popups/dialogs get found).
 *   - `ancestors` walks up to the root window; returns empty for orphans.
 *
 * ## Fixture caveats
 *
 *   * We never call `pack()` or `setVisible(true)` — the tree stays purely in-memory.
 *   * `JFrame` itself reports `isVisible == false` until shown. The walker's BFS starts
 *     at the root *unconditionally* but skips invisible siblings, so when the root is
 *     a JFrame we either flip `frame.isVisible = true` (via `internalIsVisible` hack —
 *     no, just pass `includeInvisible = true`) or pass `includeInvisible = true`.
 *   * `JFrame.contentPane.add(...)` actually adds to `contentPane`, which is itself a
 *     child of `JLayeredPane`, which is a child of `JRootPane`. So a BFS from `JFrame`
 *     visits a few Swing-internal containers before reaching the user's children.
 *     The assertions tolerate that by filtering to known node types when comparing
 *     orderings.
 */
class ComponentTreeWalkerWalkTest {

    // ====================================================================================
    // SECTION 1. Plain Container tree (no JFrame internals in the way)
    // ====================================================================================

    /**
     * Builds a tidy panel tree we fully control:
     *
     * ```
     * root: JPanel
     * ├── a: JPanel
     * │   ├── a1: JButton
     * │   └── a2: JButton
     * └── b: JPanel
     *     └── b1: JButton
     * ```
     *
     * Every container is set visible — JComponent's default is true anyway, but we
     * pin it so flipping the JDK default in some future release doesn't bite us.
     */
    private class PanelTree {
        val root = JPanel().also { it.name = "root"; it.isVisible = true }
        val a = JPanel().also { it.name = "a"; it.isVisible = true }
        val a1 = JButton("a1").also { it.name = "a1"; it.isVisible = true }
        val a2 = JButton("a2").also { it.name = "a2"; it.isVisible = true }
        val b = JPanel().also { it.name = "b"; it.isVisible = true }
        val b1 = JButton("b1").also { it.name = "b1"; it.isVisible = true }

        init {
            root.add(a)
            root.add(b)
            a.add(a1)
            a.add(a2)
            b.add(b1)
        }
    }

    @Test
    fun `walk visits root first`() {
        val t = PanelTree()
        var firstVisited: Component? = null
        var firstDepth = -1
        ComponentTreeWalker.walk(t.root, maxDepth = 5, includeInvisible = false) { c, d ->
            if (firstVisited == null) {
                firstVisited = c
                firstDepth = d
            }
            true
        }
        assertSame(t.root, firstVisited)
        assertEquals(0, firstDepth)
    }

    @Test
    fun `walk visits all descendants in BFS order`() {
        val t = PanelTree()
        val order = mutableListOf<Component>()
        ComponentTreeWalker.walk(t.root, maxDepth = 5, includeInvisible = false) { c, _ ->
            order.add(c)
            true
        }
        // BFS: root, then a, b (siblings), then a1, a2, b1.
        assertEquals(
            listOf(t.root, t.a, t.b, t.a1, t.a2, t.b1),
            order,
        )
    }

    @Test
    fun `walk respects maxDepth zero - visits only root`() {
        val t = PanelTree()
        val order = mutableListOf<Component>()
        ComponentTreeWalker.walk(t.root, maxDepth = 0, includeInvisible = false) { c, _ ->
            order.add(c)
            true
        }
        assertEquals(listOf<Component>(t.root), order)
    }

    @Test
    fun `walk respects maxDepth one - visits root and immediate children`() {
        val t = PanelTree()
        val order = mutableListOf<Component>()
        ComponentTreeWalker.walk(t.root, maxDepth = 1, includeInvisible = false) { c, _ ->
            order.add(c)
            true
        }
        // root + direct children only.
        assertEquals(listOf(t.root, t.a, t.b), order)
    }

    @Test
    fun `walk reports correct depth for each node`() {
        val t = PanelTree()
        val depths = mutableMapOf<Component, Int>()
        ComponentTreeWalker.walk(t.root, maxDepth = 5, includeInvisible = false) { c, d ->
            depths[c] = d
            true
        }
        assertEquals(0, depths[t.root])
        assertEquals(1, depths[t.a])
        assertEquals(1, depths[t.b])
        assertEquals(2, depths[t.a1])
        assertEquals(2, depths[t.a2])
        assertEquals(2, depths[t.b1])
    }

    // ====================================================================================
    // SECTION 2. Visibility gating
    // ====================================================================================

    @Test
    fun `walk skips invisible when includeInvisible is false`() {
        val t = PanelTree()
        t.a.isVisible = false // hides a + a1 + a2

        val visited = mutableListOf<Component>()
        ComponentTreeWalker.walk(t.root, maxDepth = 5, includeInvisible = false) { c, _ ->
            visited.add(c)
            true
        }
        assertTrue("root must always be visited regardless of visibility", visited.contains(t.root))
        assertFalse("expected hidden subtree to be skipped", visited.contains(t.a))
        assertFalse("a1 (child of hidden a) must be skipped", visited.contains(t.a1))
        assertFalse("a2 (child of hidden a) must be skipped", visited.contains(t.a2))
        // Sibling subtree is fine.
        assertTrue("visible sibling b must still be visited", visited.contains(t.b))
        assertTrue("child of visible sibling (b1) must still be visited", visited.contains(t.b1))
    }

    @Test
    fun `walk includes invisible when includeInvisible is true`() {
        val t = PanelTree()
        t.a.isVisible = false

        val visited = mutableListOf<Component>()
        ComponentTreeWalker.walk(t.root, maxDepth = 5, includeInvisible = true) { c, _ ->
            visited.add(c)
            true
        }
        assertTrue("hidden node a must be visited when includeInvisible=true", visited.contains(t.a))
        assertTrue("a1 (child of hidden a) must be visited when includeInvisible=true", visited.contains(t.a1))
        assertTrue("a2 (child of hidden a) must be visited when includeInvisible=true", visited.contains(t.a2))
    }

    // ====================================================================================
    // SECTION 3. Visitor return value
    // ====================================================================================

    /**
     * Tree with explicit grandchildren under `a`:
     *
     * ```
     * root
     * ├── a
     * │   └── aChild   (JPanel)
     * │       └── leaf (JButton)
     * └── b
     *     └── b1
     * ```
     */
    @Test
    fun `walk stops descent when visitor returns false for a node`() {
        val root = JPanel().also { it.isVisible = true; it.name = "root" }
        val a = JPanel().also { it.isVisible = true; it.name = "a" }
        val aChild = JPanel().also { it.isVisible = true; it.name = "aChild" }
        val leaf = JButton("leaf").also { it.isVisible = true; it.name = "leaf" }
        val b = JPanel().also { it.isVisible = true; it.name = "b" }
        val b1 = JButton("b1").also { it.isVisible = true; it.name = "b1" }
        root.add(a); root.add(b)
        a.add(aChild); aChild.add(leaf); b.add(b1)

        val visited = mutableListOf<Component>()
        ComponentTreeWalker.walk(root, maxDepth = 10, includeInvisible = false) { c, _ ->
            visited.add(c)
            // Returning false on `a` must prune a's whole subtree, but leave b alone.
            c !== a
        }
        assertTrue("Expected to visit a once before pruning", visited.contains(a))
        assertFalse("aChild must be skipped when visitor returns false for a", visited.contains(aChild))
        assertFalse("leaf (grandchild of a) must be skipped when a is pruned", visited.contains(leaf))
        assertTrue("sibling b must still be visited after a is pruned", visited.contains(b))
        assertTrue("b1 (child of unpruned sibling) must still be visited", visited.contains(b1))
    }

    // ====================================================================================
    // SECTION 4. Window ownership
    // ====================================================================================

    @Test
    fun `walk visits owned windows of a Window root`() {
        assumeFalse("Requires display server (JFrame/JDialog).", GraphicsEnvironment.isHeadless())
        val frame = JFrame()
        val dialog = JDialog(frame, "owned")
        // Neither shown — both isVisible == false by default. Use includeInvisible=true.
        val visited = mutableListOf<Component>()
        try {
            ComponentTreeWalker.walk(frame, maxDepth = 10, includeInvisible = true) { c, _ ->
                visited.add(c)
                true
            }
        } finally {
            dialog.dispose()
            frame.dispose()
        }
        assertTrue("Expected owned dialog to be reached via ownedWindows", visited.contains(dialog))
    }

    // ====================================================================================
    // SECTION 5. JFrame contentPane traversal (sanity check the swing-internal containers)
    // ====================================================================================

    @Test
    fun `walk through JFrame visits the JPanel added to contentPane`() {
        assumeFalse("Requires display server (JFrame).", GraphicsEnvironment.isHeadless())
        val frame = JFrame()
        val panel = JPanel().also { it.name = "myPanel"; it.isVisible = true }
        val button = JButton("a").also { it.name = "myButton"; it.isVisible = true }
        panel.add(button)
        frame.contentPane.add(panel)
        // frame is not displayable yet — pass includeInvisible=true so the walker doesn't
        // skip the frame itself.
        val visited = mutableListOf<Component>()
        try {
            ComponentTreeWalker.walk(frame, maxDepth = 20, includeInvisible = true) { c, _ ->
                visited.add(c)
                true
            }
        } finally {
            frame.dispose()
        }
        // The user's nodes must be in there; Swing-internal containers (JRootPane,
        // JLayeredPane, JPanel-as-contentPane) sit in between and we don't pin those.
        assertTrue("Expected user JPanel in the walk", visited.contains(panel))
        assertTrue("Expected user JButton in the walk", visited.contains(button))
        // Root must come first regardless of internals.
        assertSame(frame, visited.first())
    }

    // ====================================================================================
    // SECTION 6. ancestors()
    // ====================================================================================

    @Test
    fun `ancestors returns chain from parent up to top-level window`() {
        assumeFalse("Requires display server (JFrame).", GraphicsEnvironment.isHeadless())
        val frame = JFrame()
        val panel = JPanel().also { it.name = "myPanel" }
        val button = JButton("a").also { it.name = "myButton" }
        panel.add(button)
        frame.contentPane.add(panel)
        try {
            val chain = ComponentTreeWalker.ancestors(button)
            assertFalse("Expected non-empty ancestor chain", chain.isEmpty())
            assertSame(
                "Expected immediate parent (the JPanel we added the button to) first",
                panel,
                chain.first(),
            )
            assertSame(
                "Expected JFrame at the top of the chain",
                frame,
                chain.last(),
            )
            // And along the way we should encounter the contentPane.
            assertTrue(
                "Expected contentPane somewhere in the chain",
                chain.contains(frame.contentPane),
            )
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `ancestors is empty for an orphan component`() {
        val orphan = JPanel()
        assertEquals(emptyList<Component>(), ComponentTreeWalker.ancestors(orphan))
    }

    @Test
    fun `ancestors handles a single-parent container`() {
        // Sanity: parent of a JButton inside a JPanel is exactly that panel and nothing
        // else (the panel has no further parent).
        val panel = JPanel()
        val button = JButton("a")
        panel.add(button)
        val chain = ComponentTreeWalker.ancestors(button)
        assertEquals(1, chain.size)
        assertSame(panel, chain.first())
    }

    // ====================================================================================
    // SECTION 7. Defensive: containers with null children entries are tolerated
    // ====================================================================================

    @Test
    fun `walk over an empty container yields just the root`() {
        val empty: Container = JPanel().also { it.isVisible = true }
        val visited = mutableListOf<Component>()
        ComponentTreeWalker.walk(empty, maxDepth = 5, includeInvisible = false) { c, _ ->
            visited.add(c)
            true
        }
        assertEquals(listOf<Component>(empty), visited)
    }

    @Test
    fun `walk maxDepth larger than tree depth visits everyone exactly once`() {
        val t = PanelTree()
        val counts = mutableMapOf<Component, Int>()
        ComponentTreeWalker.walk(t.root, maxDepth = 999, includeInvisible = false) { c, _ ->
            counts[c] = (counts[c] ?: 0) + 1
            true
        }
        // Each node visited exactly once.
        assertEquals(1, counts[t.root])
        assertEquals(1, counts[t.a])
        assertEquals(1, counts[t.b])
        assertEquals(1, counts[t.a1])
        assertEquals(1, counts[t.a2])
        assertEquals(1, counts[t.b1])
        // And we visited everyone.
        assertNotNull(counts[t.a1])
        assertNotNull(counts[t.a2])
        assertNotNull(counts[t.b1])
    }
}
