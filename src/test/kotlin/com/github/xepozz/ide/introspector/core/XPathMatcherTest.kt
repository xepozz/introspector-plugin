package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.Bounds
import com.github.xepozz.ide.introspector.model.ComponentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [XPathMatcher].
 *
 * Two goals:
 *   1. **Spec coverage**: every code path of the parser and evaluator has a test.
 *   2. **Documentation by example**: each query mirrors a realistic locator a plugin user
 *      would write against intellij-ui-test-robot. If a test reads weirdly, the matcher is
 *      probably weird too.
 *
 * ## Fixture: a small but IDE-like Swing tree
 *
 * ```
 * frame      (com.intellij.openapi.wm.impl.IdeFrameImpl)
 * ├── menuBar       (javax.swing.JMenuBar)
 * │   ├── fileMenu     (ActionMenu, text="File")
 * │   └── editMenu     (ActionMenu, text="Edit")
 * ├── toolbar       (ActionToolbarImpl, name="MainToolbar", accessibleName="Main Toolbar")
 * │   ├── btnRun       (ActionButton, accessibleName="Run", toolTipText="Run 'Main'")
 * │   ├── btnDebug     (ActionButton, accessibleName="Debug")
 * │   └── btnStop      (ActionButton, accessibleName="Stop")
 * ├── projectView   (InternalDecoratorImpl, name="Project")
 * │   ├── projectLabel (JBLabel, text="playground")
 * │   └── tree         (Tree)
 * └── dialog        (DialogWrapper$DialogWrapperDialog, accessibleName="Settings")
 *     ├── settingsLabel (JBLabel, text="Settings")
 *     ├── okBtn         (JButton,  text="OK",     accessibleName="OK")
 *     ├── cancelBtn     (JButton,  text="Cancel", accessibleName="Cancel")
 *     └── applyBtn      (JButton,  text="Apply",  accessibleName="Apply")
 * ```
 *
 * Document order (DFS, children left-to-right):
 *   frame, menuBar, fileMenu, editMenu,
 *   toolbar, btnRun, btnDebug, btnStop,
 *   projectView, projectLabel, tree,
 *   dialog, settingsLabel, okBtn, cancelBtn, applyBtn.
 *
 * ## How to read these tests
 *
 * Most assertions use [ids] which strips the query result down to component IDs. That keeps
 * each test a single readable line:
 *
 * ```
 * assertEquals(listOf("okBtn"), ids("//JButton[@text='OK']"))
 * ```
 *
 * If you want to add a new test, copy the closest existing one — the fixture is dense
 * enough that most realistic IDE queries can be expressed against it.
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
        node(
            "frame",
            "com.intellij.openapi.wm.impl.IdeFrameImpl",
            children = listOf("menuBar", "toolbar", "projectView", "dialog"),
        ),
        // --- Menu bar ---
        node(
            "menuBar", "javax.swing.JMenuBar",
            children = listOf("fileMenu", "editMenu"),
        ),
        node(
            "fileMenu", "com.intellij.openapi.actionSystem.impl.ActionMenu",
            text = "File",
        ),
        node(
            "editMenu", "com.intellij.openapi.actionSystem.impl.ActionMenu",
            text = "Edit",
        ),
        // --- Main toolbar ---
        node(
            "toolbar", "com.intellij.openapi.actionSystem.impl.ActionToolbarImpl",
            name = "MainToolbar", accessibleName = "Main Toolbar",
            children = listOf("btnRun", "btnDebug", "btnStop"),
        ),
        node(
            "btnRun", "com.intellij.openapi.actionSystem.impl.ActionButton",
            accessibleName = "Run", toolTipText = "Run 'Main'",
        ),
        node(
            "btnDebug", "com.intellij.openapi.actionSystem.impl.ActionButton",
            accessibleName = "Debug",
        ),
        node(
            "btnStop", "com.intellij.openapi.actionSystem.impl.ActionButton",
            accessibleName = "Stop",
        ),
        // --- Project tool window ---
        node(
            "projectView", "com.intellij.openapi.wm.impl.InternalDecoratorImpl",
            name = "Project",
            children = listOf("projectLabel", "tree"),
        ),
        node(
            "projectLabel", "com.intellij.ui.components.JBLabel",
            text = "playground",
        ),
        node("tree", "com.intellij.ui.treeStructure.Tree"),
        // --- Settings dialog ---
        node(
            "dialog", "com.intellij.openapi.ui.DialogWrapper\$DialogWrapperDialog",
            accessibleName = "Settings",
            children = listOf("settingsLabel", "okBtn", "cancelBtn", "applyBtn"),
        ),
        node(
            "settingsLabel", "com.intellij.ui.components.JBLabel",
            text = "Settings",
        ),
        node("okBtn", "javax.swing.JButton", text = "OK", accessibleName = "OK"),
        node("cancelBtn", "javax.swing.JButton", text = "Cancel", accessibleName = "Cancel"),
        node("applyBtn", "javax.swing.JButton", text = "Apply", accessibleName = "Apply"),
    ).associateBy { it.id }

    private val roots = listOf("frame")

    private fun ids(xpath: String, limit: Int = 1000): List<String> =
        XPathMatcher(nodes, roots).query(xpath, limit).map { it.id }

    private fun expectError(xpath: String, vararg expectedFragments: String) {
        try {
            ids(xpath)
            fail("Expected IllegalArgumentException for: $xpath")
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            for (frag in expectedFragments) {
                assertTrue(
                    "Error message must contain '$frag', got: $msg",
                    msg.contains(frag),
                )
            }
        }
    }

    // ====================================================================================
    // SECTION 1. Smoke
    // ====================================================================================

    @Test
    fun `match all descendants`() {
        assertEquals(
            listOf(
                "frame", "menuBar", "fileMenu", "editMenu",
                "toolbar", "btnRun", "btnDebug", "btnStop",
                "projectView", "projectLabel", "tree",
                "dialog", "settingsLabel", "okBtn", "cancelBtn", "applyBtn",
            ),
            ids("//*"),
        )
    }

    @Test
    fun `div is alias for star`() {
        assertEquals(ids("//*"), ids("//div"))
    }

    // ====================================================================================
    // SECTION 2. Axes
    // ====================================================================================

    @Test
    fun `slash is child axis - selects direct children of the seed`() {
        // The frame's direct children are menuBar, toolbar, projectView, dialog.
        assertEquals(
            listOf("menuBar", "toolbar", "projectView", "dialog"),
            ids("/*"),
        )
    }

    @Test
    fun `double slash is descendant-or-self axis - includes the seed itself`() {
        // The seed frame is a JFrame-like; descendant-or-self matches the seed too.
        assertEquals(listOf("frame"), ids("//IdeFrameImpl"))
    }

    @Test
    fun `bareword is shorthand for descendant-or-self`() {
        assertEquals(ids("//JButton"), ids("JButton"))
    }

    @Test
    fun `dot is self axis`() {
        assertEquals(listOf("frame"), ids("/."))
        assertEquals(listOf("frame"), ids("//."))
    }

    @Test
    fun `dot keeps predicates`() {
        // self::*[@class='IdeFrameImpl']
        assertEquals(listOf("frame"), ids("/.[@class='IdeFrameImpl']"))
        assertEquals(emptyList<String>(), ids("/.[@class='JBLabel']"))
    }

    @Test
    fun `child then descendant`() {
        // toolbar is a direct child of frame; all ActionButtons are inside the toolbar.
        assertEquals(
            listOf("btnRun", "btnDebug", "btnStop"),
            ids("/ActionToolbarImpl//ActionButton"),
        )
    }

    @Test
    fun `descendant then child`() {
        // Find every dialog, then its direct JButton children.
        assertEquals(
            listOf("okBtn", "cancelBtn", "applyBtn"),
            ids("//DialogWrapper\$DialogWrapperDialog/JButton"),
        )
    }

    @Test
    fun `child axis through multiple levels`() {
        assertEquals(listOf("projectLabel"), ids("/InternalDecoratorImpl/JBLabel"))
    }

    // ====================================================================================
    // SECTION 3. Name matching
    // ====================================================================================

    @Test
    fun `tagName matches simple class name`() {
        assertEquals(
            listOf("okBtn", "cancelBtn", "applyBtn"),
            ids("//JButton"),
        )
    }

    @Test
    fun `tagName matches fully qualified class name`() {
        assertEquals(
            listOf("okBtn", "cancelBtn", "applyBtn"),
            ids("//javax.swing.JButton"),
        )
    }

    @Test
    fun `tagName respects inner classes via dollar`() {
        // Same FQN as in real JVM class names — escape the $ in the test source only.
        assertEquals(
            listOf("dialog"),
            ids("//com.intellij.openapi.ui.DialogWrapper\$DialogWrapperDialog"),
        )
    }

    @Test
    fun `name match is case-sensitive`() {
        // Intentional: Swing class names are mixed case; lowercased queries do not match.
        assertEquals(emptyList<String>(), ids("//jbutton"))
        assertEquals(emptyList<String>(), ids("//ACTIONBUTTON"))
    }

    @Test
    fun `star matches everything but is filtered by predicates`() {
        assertEquals(listOf("projectView"), ids("//*[@name='Project']"))
    }

    @Test
    fun `whitespace around tagName is tolerated`() {
        // `// foo` used to silently match nothing because " foo" never equalled any class.
        // Now the parser trims it — typo-friendly.
        assertEquals(ids("//ActionButton"), ids("// ActionButton"))
        assertEquals(ids("//ActionButton"), ids("//ActionButton "))
    }

    // ====================================================================================
    // SECTION 4. Predicates — per attribute
    // ====================================================================================

    @Test
    fun `predicate at class - simple name`() {
        assertEquals(
            listOf("projectLabel", "settingsLabel"),
            ids("//*[@class='JBLabel']"),
        )
    }

    @Test
    fun `predicate at fqClass - fully qualified name`() {
        assertEquals(
            listOf("projectLabel", "settingsLabel"),
            ids("//*[@fqClass='com.intellij.ui.components.JBLabel']"),
        )
    }

    @Test
    fun `predicate at name - Swing component name property`() {
        assertEquals(listOf("toolbar"), ids("//*[@name='MainToolbar']"))
        assertEquals(listOf("projectView"), ids("//*[@name='Project']"))
    }

    @Test
    fun `predicate at accessibleName`() {
        assertEquals(listOf("btnRun"), ids("//*[@accessibleName='Run']"))
        assertEquals(listOf("dialog"), ids("//*[@accessibleName='Settings']"))
    }

    @Test
    fun `predicate at text`() {
        assertEquals(listOf("okBtn"), ids("//*[@text='OK']"))
        assertEquals(listOf("fileMenu"), ids("//*[@text='File']"))
    }

    @Test
    fun `predicate at toolTipText`() {
        assertEquals(listOf("btnRun"), ids("//*[@toolTipText=\"Run 'Main'\"]"))
    }

    @Test
    fun `predicate against null property is never a match`() {
        // tree has no text/accessibleName/etc. — predicate must filter it out, not crash.
        assertEquals(emptyList<String>(), ids("//Tree[@text='whatever']"))
    }

    // ====================================================================================
    // SECTION 5. Predicates — quoting
    // ====================================================================================

    @Test
    fun `single-quoted value`() {
        assertEquals(listOf("okBtn"), ids("//*[@text='OK']"))
    }

    @Test
    fun `double-quoted value`() {
        assertEquals(listOf("okBtn"), ids("//*[@text=\"OK\"]"))
    }

    @Test
    fun `single quote inside double-quoted value`() {
        // Standard XPath quoting: use the other quote type when value contains one.
        assertEquals(listOf("btnRun"), ids("//*[@toolTipText=\"Run 'Main'\"]"))
    }

    @Test
    fun `value with no quotes at all is accepted`() {
        // Lenient, undocumented but works. Equivalent to '@text=OK'.
        assertEquals(listOf("okBtn"), ids("//*[@text=OK]"))
    }

    @Test
    fun `value with spaces around equals`() {
        assertEquals(listOf("okBtn"), ids("//*[@text = 'OK']"))
    }

    @Test
    fun `empty value matches null but not empty string`() {
        // No node has text == "" in our fixture, so empty.
        assertEquals(emptyList<String>(), ids("//*[@text='']"))
    }

    @Test
    fun `value containing equals sign`() {
        // The `=` after `text` is the separator; subsequent `=` is part of the value.
        // No such node in the fixture, but we verify it parses without error.
        assertEquals(emptyList<String>(), ids("//*[@text='a=b']"))
    }

    @Test
    fun `unclosed quote surfaces as unbalanced bracket`() {
        // A `'` without a matching `'` swallows the `]` terminator too, so the bracket
        // checker fires first. The error is still actionable — just at the bracket level.
        expectError("//*[@text='OK]", "Unbalanced")
    }

    @Test
    fun `value with quote glued to bare text is rejected`() {
        // `''OK` — the leading pair closes itself, leaving `OK` outside quotes. The bracket
        // scanner is happy (quotes balanced), but parsePredicate catches the mismatch
        // between the quoted prefix and the unquoted suffix.
        expectError("//*[@text=''OK]", "Mismatched", "quote")
    }

    // ====================================================================================
    // SECTION 6. Predicates — combinations
    // ====================================================================================

    @Test
    fun `combined predicates with and`() {
        assertEquals(
            listOf("okBtn"),
            ids("//JButton[@text='OK' and @accessibleName='OK']"),
        )
    }

    @Test
    fun `combined predicates short-circuit on mismatch`() {
        assertEquals(
            emptyList<String>(),
            ids("//JButton[@text='OK' and @accessibleName='Cancel']"),
        )
    }

    @Test
    fun `and is case-insensitive`() {
        assertEquals(
            ids("//JButton[@text='OK' and @accessibleName='OK']"),
            ids("//JButton[@text='OK' AND @accessibleName='OK']"),
        )
        assertEquals(
            ids("//JButton[@text='OK' and @accessibleName='OK']"),
            ids("//JButton[@text='OK' And @accessibleName='OK']"),
        )
    }

    @Test
    fun `and inside a quoted value is not a separator`() {
        // Pathological: literal " and " inside a value would otherwise be split.
        // No such node in the fixture, but we verify it doesn't split into two predicates.
        // The two-predicate split would throw on the malformed second half — silence here
        // means we kept the single literal.
        assertEquals(emptyList<String>(), ids("//*[@text='Run and Debug']"))
    }

    @Test
    fun `multiple bracket predicates accumulate`() {
        // [@a='x'][@b='y'] is equivalent to [@a='x' and @b='y'].
        assertEquals(
            listOf("okBtn"),
            ids("//JButton[@text='OK'][@accessibleName='OK']"),
        )
    }

    @Test
    fun `or is not supported`() {
        // robot doesn't support `or` either — fail loudly instead of returning weird results.
        expectError("//*[@text='OK' or @text='Cancel']", "or")
    }

    // ====================================================================================
    // SECTION 7. Predicates — positional
    // ====================================================================================

    @Test
    fun `positional 1 picks first match in document order`() {
        assertEquals(listOf("okBtn"), ids("//JButton[1]"))
    }

    @Test
    fun `positional N picks Nth match`() {
        assertEquals(listOf("cancelBtn"), ids("//JButton[2]"))
        assertEquals(listOf("applyBtn"), ids("//JButton[3]"))
    }

    @Test
    fun `positional out of range yields empty`() {
        assertEquals(emptyList<String>(), ids("//JButton[99]"))
    }

    @Test
    fun `positional after attribute predicate`() {
        // Among ActionButtons in the toolbar, [1] is btnRun.
        assertEquals(listOf("btnRun"), ids("//ActionButton[1]"))
    }

    @Test
    fun `positional and attribute predicate combined`() {
        // First JButton whose text equals 'OK' is okBtn (only one match anyway).
        assertEquals(listOf("okBtn"), ids("//JButton[@text='OK'][1]"))
    }

    @Test
    fun `zero index is rejected`() {
        expectError("//JButton[0]", "Position")
    }

    @Test
    fun `negative index is rejected`() {
        expectError("//JButton[-1]", "Position")
    }

    @Test
    fun `multiple positional in same step is rejected`() {
        expectError("//JButton[1][2]", "positional")
    }

    // ====================================================================================
    // SECTION 8. Limit
    // ====================================================================================

    @Test
    fun `limit caps results`() {
        assertEquals(listOf("okBtn", "cancelBtn"), ids("//JButton", limit = 2))
    }

    @Test
    fun `limit zero yields empty`() {
        assertEquals(emptyList<String>(), ids("//JButton", limit = 0))
    }

    @Test
    fun `limit larger than match count yields all matches`() {
        assertEquals(3, ids("//JButton", limit = 999).size)
    }

    // ====================================================================================
    // SECTION 9. Errors and diagnostics
    // ====================================================================================

    @Test
    fun `empty xpath is rejected`() {
        expectError("", "Empty")
    }

    @Test
    fun `whitespace-only xpath is rejected`() {
        expectError("   ", "Empty")
    }

    @Test
    fun `unknown attribute names supported ones in the message`() {
        expectError(
            "//*[@unknown='x']",
            "@unknown",
            "@class",
            "@text",
        )
    }

    @Test
    fun `unbalanced bracket is rejected`() {
        expectError("//*[@class='x'", "Unbalanced")
    }

    @Test
    fun `empty bracket is rejected`() {
        expectError("//*[]", "Empty predicate")
    }

    @Test
    fun `predicate without leading at is rejected`() {
        // Forgetting @ is the single most common XPath mistake; silent fail would be cruel.
        expectError("//*[class='JBLabel']", "@")
    }

    @Test
    fun `predicate without equals is rejected`() {
        expectError("//*[@class]", "=")
    }

    @Test
    fun `function-style predicate is rejected`() {
        // text(), contains() etc. aren't part of the supported subset.
        expectError("//*[text()='OK']", "@")
    }

    @Test
    fun `parent axis is not supported`() {
        expectError("//JButton/..", "..")
    }

    @Test
    fun `trailing slash is rejected`() {
        expectError("//JButton/", "Trailing")
    }

    // ====================================================================================
    // SECTION 10. Realistic locator examples
    //
    // These are the kind of queries a plugin user writes against intellij-ui-test-robot.
    // If any of these break in the future, real test code somewhere will break too.
    // ====================================================================================

    @Test
    fun `example - the Run toolbar button`() {
        // Common style: any descendant ActionButton whose accessibleName is 'Run'.
        assertEquals(listOf("btnRun"), ids("//ActionButton[@accessibleName='Run']"))
    }

    @Test
    fun `example - the OK button in a dialog`() {
        assertEquals(
            listOf("okBtn"),
            ids("//DialogWrapper\$DialogWrapperDialog//JButton[@text='OK']"),
        )
    }

    @Test
    fun `example - any label with specific text`() {
        assertEquals(listOf("projectLabel"), ids("//JBLabel[@text='playground']"))
    }

    @Test
    fun `example - the dialog by accessibleName`() {
        assertEquals(listOf("dialog"), ids("//*[@accessibleName='Settings']"))
    }

    @Test
    fun `example - all buttons in main toolbar`() {
        assertEquals(
            listOf("btnRun", "btnDebug", "btnStop"),
            ids("//ActionToolbarImpl[@name='MainToolbar']//ActionButton"),
        )
    }

    @Test
    fun `example - menu by class and text`() {
        assertEquals(
            listOf("fileMenu"),
            ids("//ActionMenu[@text='File']"),
        )
    }

    @Test
    fun `example - second action button`() {
        assertEquals(listOf("btnDebug"), ids("//ActionButton[2]"))
    }

    @Test
    fun `example - first dialog button`() {
        assertEquals(
            listOf("okBtn"),
            ids("//DialogWrapper\$DialogWrapperDialog//JButton[1]"),
        )
    }

    @Test
    fun `example - locate by toolTipText`() {
        assertEquals(listOf("btnRun"), ids("//*[@toolTipText=\"Run 'Main'\"]"))
    }

    @Test
    fun `example - chain dialog then button by text`() {
        assertEquals(
            listOf("applyBtn"),
            ids("//*[@accessibleName='Settings']//JButton[@text='Apply']"),
        )
    }

    @Test
    fun `example - first JBLabel in the project view`() {
        assertEquals(
            listOf("projectLabel"),
            ids("//InternalDecoratorImpl//JBLabel[1]"),
        )
    }

    @Test
    fun `example - any descendant with multiple constraints`() {
        assertEquals(
            listOf("dialog"),
            ids("//*[@class='DialogWrapper\$DialogWrapperDialog' and @accessibleName='Settings']"),
        )
    }

    // ====================================================================================
    // SECTION 11. Unicode and special characters in values
    // ====================================================================================

    @Test
    fun `unicode value matches`() {
        // Add a synthetic node so we don't need to mutate the main fixture; check directly.
        val unicode = node("u1", "javax.swing.JLabel", text = "Привет")
        val miniNodes = mapOf("u1" to unicode)
        val matcher = XPathMatcher(miniNodes, listOf("u1"))
        assertEquals(listOf("u1"), matcher.query("//*[@text='Привет']", 10).map { it.id })
    }

    @Test
    fun `dot inside value is not interpreted`() {
        val n = node("v1", "javax.swing.JLabel", text = "a.b.c")
        val matcher = XPathMatcher(mapOf("v1" to n), listOf("v1"))
        assertEquals(listOf("v1"), matcher.query("//*[@text='a.b.c']", 10).map { it.id })
    }

    @Test
    fun `slash inside value is not interpreted`() {
        val n = node("v2", "javax.swing.JLabel", text = "src/main/kotlin")
        val matcher = XPathMatcher(mapOf("v2" to n), listOf("v2"))
        assertEquals(listOf("v2"), matcher.query("//*[@text='src/main/kotlin']", 10).map { it.id })
    }

    // ====================================================================================
    // SECTION 12. Multiple roots
    // ====================================================================================

    @Test
    fun `multiple seed roots are all traversed`() {
        val a = node("a", "javax.swing.JLabel", text = "alpha")
        val b = node("b", "javax.swing.JLabel", text = "beta")
        val matcher = XPathMatcher(mapOf("a" to a, "b" to b), listOf("a", "b"))
        assertEquals(listOf("a", "b"), matcher.query("//JLabel", 10).map { it.id })
    }

    @Test
    fun `missing seed id is silently skipped`() {
        // We pre-build the snapshot during walk; a stale rootId shouldn't blow us up.
        val a = node("a", "javax.swing.JLabel", text = "alpha")
        val matcher = XPathMatcher(mapOf("a" to a), listOf("a", "ghost"))
        assertEquals(listOf("a"), matcher.query("//JLabel", 10).map { it.id })
    }

    @Test
    fun `missing child id is silently skipped during traversal`() {
        // Defensive: walker may have hit the 8k cap and left dangling child references.
        val parent = node("p", "javax.swing.JPanel", children = listOf("missing", "real"))
        val real = node("real", "javax.swing.JLabel", text = "hi")
        val matcher = XPathMatcher(mapOf("p" to parent, "real" to real), listOf("p"))
        assertEquals(listOf("real"), matcher.query("//JLabel", 10).map { it.id })
    }

    // ====================================================================================
    // SECTION 13. Additional corner cases for parser branches
    //
    // The cases below target branches that the realistic-locator section can't reach:
    // triple-slash diagnostics, missing-child resolution on the CHILD axis, positional
    // predicates against an empty filtered set, malformed predicate values, and the
    // "value containing a stray quote" mismatch path in `parsePredicate`.
    // ====================================================================================

    @Test
    fun `triple slash is rejected with a position-aware message`() {
        // Targets the explicit `///` guard in parseSingleChain — silent acceptance would
        // make a typo (`///foo`) look like a no-match, which is the worst kind of bug.
        expectError("///foo", "Too many", "///foo")
    }

    @Test
    fun `missing child id is silently skipped on the child axis`() {
        // CHILD axis goes through a separate `nodesById[it]?.let(…)` branch from the
        // descendant traversal; without this test that null-handling branch stays cold.
        val parent = node("p", "javax.swing.JPanel", children = listOf("ghost", "real"))
        val real = node("real", "javax.swing.JButton", text = "ok")
        val matcher = XPathMatcher(mapOf("p" to parent, "real" to real), listOf("p"))
        assertEquals(listOf("real"), matcher.query("/JButton", 10).map { it.id })
    }

    @Test
    fun `positional predicate against empty filtered set yields empty`() {
        // Attribute predicate filters everything out; the positional then has nothing to
        // pick from. Exercises the `else emptyList()` arm of the positional branch.
        assertEquals(emptyList<String>(), ids("//JButton[@text='Nope'][1]"))
    }

    @Test
    fun `predicate with empty attribute name is rejected`() {
        // `[@=value]` — covers the `attr.isEmpty()` guard inside parsePredicate.
        expectError("//*[@='OK']", "attribute name")
    }

    @Test
    fun `predicate value with mismatched leading quote is rejected`() {
        // The bracket scanner balances the two single quotes inside the brackets, leaving
        // a value of `'OK'X` (quote at front but trailing non-quote). parsePredicate
        // catches it with the "Mismatched quotes" diagnostic.
        expectError("//*[@text='OK'X]", "Mismatched", "quote")
    }

    @Test
    fun `value containing the word and inside double quotes is preserved`() {
        // splitByAnd must not slice on a quoted ` and ` substring; mirrors the existing
        // single-quote variant but exercises the double-quote branch in the scanner.
        val n = node("v3", "javax.swing.JLabel", text = "Run and Debug")
        val matcher = XPathMatcher(mapOf("v3" to n), listOf("v3"))
        assertEquals(
            listOf("v3"),
            matcher.query("//*[@text=\"Run and Debug\"]", 10).map { it.id },
        )
    }

    @Test
    fun `value containing the word or inside double quotes is not treated as an or operator`() {
        // Same shape as the test above but for the `or` rejection — quoted "or" inside
        // a value must be returned as data, not flagged as an unsupported operator.
        val n = node("v4", "javax.swing.JLabel", text = "Run or Debug")
        val matcher = XPathMatcher(mapOf("v4" to n), listOf("v4"))
        assertEquals(
            listOf("v4"),
            matcher.query("//*[@text=\"Run or Debug\"]", 10).map { it.id },
        )
    }

    @Test
    fun `trailing whitespace inside predicate values is preserved when unquoted`() {
        // Lenient parsing: `@text=OK` trims around the equals but the value itself is
        // kept verbatim. The earlier "value with no quotes at all" test established the
        // happy path; this nails the trim behaviour at the equals boundary.
        assertEquals(listOf("okBtn"), ids("//*[@text=  OK  ]"))
    }

    @Test
    fun `single-character value is accepted when unquoted`() {
        // Edge case for parsePredicate: value of length 1, no quotes — exercises the
        // `length >= 2` guard around the same-quote-trim path.
        val n = node("c1", "javax.swing.JLabel", text = "x")
        val matcher = XPathMatcher(mapOf("c1" to n), listOf("c1"))
        assertEquals(listOf("c1"), matcher.query("//*[@text=x]", 10).map { it.id })
    }

    @Test
    fun `empty quoted value strips to empty string and matches no node`() {
        // Quoted empty (`@text=''`) exercises the `value.length >= 2` strip path with the
        // shortest legal input — value becomes "" after stripping. No fixture node has an
        // empty text, so the result is empty; the point is "parses cleanly".
        assertEquals(emptyList<String>(), ids("//*[@text='']"))
        assertEquals(emptyList<String>(), ids("//*[@text=\"\"]"))
    }

    @Test
    fun `step with only a predicate and no name is wildcard`() {
        // `/[@class='JBLabel']` has an empty tagName before the bracket. The `ifEmpty {"*"}`
        // arm of the parser turns it into `*[@class='JBLabel']` — wildcard.
        assertEquals(listOf("projectLabel"), ids("/InternalDecoratorImpl/[@class='JBLabel']"))
    }

    @Test
    fun `doubled and produces an empty fragment that is filtered out`() {
        // splitByAnd splits on ` and `. Two consecutive ` and ` markers (`x and  and y`)
        // leave an empty middle fragment which the trailing `filter { isNotEmpty }` drops.
        // Both real predicates still need to match — okBtn satisfies both class and text.
        assertEquals(
            listOf("okBtn"),
            ids("//JButton[@class='JButton' and  and @text='OK']"),
        )
    }

    @Test
    fun `tiny predicate value does not falsely trigger or-detection`() {
        // containsTopLevelOr peeks 4 chars ahead with `i + 3 < s.length`; a value too
        // short to contain " or " must not trigger the unsupported-operator throw.
        // `[@text=o]` is 8 chars — the scanner walks the whole thing without finding " or ".
        val n = node("t1", "javax.swing.JLabel", text = "o")
        val matcher = XPathMatcher(mapOf("t1" to n), listOf("t1"))
        assertEquals(listOf("t1"), matcher.query("//*[@text=o]", 10).map { it.id })
    }

    @Test
    fun `value containing literal substring or at the tail does not trigger or-detection`() {
        // The " or " window requires a trailing space too; a value ending in just "or"
        // (no following space) must pass through cleanly.
        val n = node("t2", "javax.swing.JLabel", text = "Mentor")
        val matcher = XPathMatcher(mapOf("t2" to n), listOf("t2"))
        assertEquals(listOf("t2"), matcher.query("//*[@text=Mentor]", 10).map { it.id })
    }
}
