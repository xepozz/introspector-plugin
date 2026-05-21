package com.github.xepozz.ide.introspector.core

import com.github.xepozz.ide.introspector.model.ComponentInfo

/**
 * Tiny XPath-like matcher operating on already-serialised [ComponentInfo] trees so we never
 * have to bounce on the EDT during evaluation. Supports the subset used by
 * `intellij-ui-test-robot`:
 *
 *  - `/foo`  — child step
 *  - `//foo` — descendant-or-self step
 *  - `.`     — self (works with predicates: `.[@class='X']` filters the context node)
 *  - `*`     — any element
 *  - `div`   — wildcard alias used by remote-robot ("any component")
 *  - predicates `[@attr='value']` or `[@attr=\"value\"]` with `and`-joined predicates
 *  - positional predicate `[N]` (1-based, document order, applied AFTER attribute predicates)
 *
 * Supported attributes: `@class` (simple name), `@fqClass` (fully qualified),
 * `@name`, `@accessibleName`, `@text`, `@toolTipText`. Predicates only test equality.
 *
 * Name matching (`/JButton`, `//JBLabel`) is case-sensitive and accepts either the simple
 * class name or the fully qualified one. An unknown `@attr` raises [IllegalArgumentException]
 * — silent no-match would be a debugging trap.
 *
 * Traversal of `//` / `*` uses **document order** (depth-first, children left-to-right).
 * This matters when positional predicates pick a specific match (`//JButton[2]`).
 */
class XPathMatcher(
    private val nodesById: Map<String, ComponentInfo>,
    private val rootIds: List<String>,
) {

    fun query(xpath: String, limit: Int): List<ComponentInfo> {
        val steps = parseSteps(xpath)
        if (steps.isEmpty()) return emptyList()
        val seeds: List<ComponentInfo> = rootIds.mapNotNull { nodesById[it] }
        var current: List<ComponentInfo> = seeds
        for (step in steps) {
            val candidates = mutableListOf<ComponentInfo>()
            for (ctx in current) {
                when (step.axis) {
                    Axis.CHILD -> ctx.children.forEach { nodesById[it]?.let(candidates::add) }
                    Axis.DESCENDANT_OR_SELF -> collectDescendants(ctx, candidates)
                    Axis.SELF -> candidates.add(ctx)
                }
            }
            val filtered = candidates.filter { matchesNameAndPredicates(it, step) }
            current = if (step.positional != null) {
                val idx = step.positional - 1
                if (idx in filtered.indices) listOf(filtered[idx]) else emptyList()
            } else filtered
            if (current.isEmpty()) break
        }
        return current.take(limit)
    }

    private fun collectDescendants(start: ComponentInfo, out: MutableList<ComponentInfo>) {
        // Iterative depth-first traversal in document order (children left to right).
        // Using removeLast + reversed push gives DFS; removeFirst + in-order push would be
        // BFS, which breaks positional predicates that target the Nth match in document
        // order — that's the convention `intellij-ui-test-robot` follows.
        val stack: ArrayDeque<ComponentInfo> = ArrayDeque()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out.add(n)
            for (i in n.children.indices.reversed()) {
                val c = nodesById[n.children[i]] ?: continue
                stack.addLast(c)
            }
        }
    }

    private fun matchesNameAndPredicates(node: ComponentInfo, step: Step): Boolean {
        if (step.tagName != "*" && step.tagName != "div") {
            // tagName matches either the simple class name or the fully qualified one.
            val simple = node.className.substringAfterLast('.')
            if (!simple.equals(step.tagName, ignoreCase = false) &&
                !node.className.equals(step.tagName, ignoreCase = false)
            ) return false
        }
        for ((attr, expected) in step.predicates) {
            val actual: String? = when (attr) {
                "class" -> node.className.substringAfterLast('.')
                "fqClass" -> node.className
                "name" -> node.name
                "accessibleName" -> node.accessibleName
                "text" -> node.text
                "toolTipText" -> node.toolTipText
                else -> throw IllegalArgumentException(
                    "Unknown XPath attribute @$attr. " +
                            "Supported: @class, @fqClass, @name, @accessibleName, @text, @toolTipText.",
                )
            }
            if (actual != expected) return false
        }
        return true
    }

    // ---------------- Parser ----------------

    private enum class Axis { CHILD, DESCENDANT_OR_SELF, SELF }

    private data class Step(
        val axis: Axis,
        val tagName: String,
        val predicates: List<Pair<String, String>>,
        val positional: Int?,
    )

    private fun parseSteps(input: String): List<Step> {
        // Leading `/` or `//` decides whether the first step is CHILD or DESCENDANT_OR_SELF;
        // a bareword (no leading slash) is treated as `//bareword` — convenient shorthand.
        val s = input.trim()
        if (s.isEmpty()) {
            throw IllegalArgumentException("Empty XPath expression.")
        }
        // Trailing `/` after a step name is almost always a typo (`//foo/` instead of
        // `//foo`); accept only the bare `/` and `//` "select-everything" forms.
        if (s.length > 2 && s.endsWith("/")) {
            throw IllegalArgumentException("Trailing '/' in XPath: '$s'.")
        }
        val normalized = if (s.startsWith("/")) s else "//$s"
        return parseSingleChain(normalized)
    }

    private fun parseSingleChain(s: String): List<Step> {
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < s.length) {
            val axis: Axis = when {
                s.startsWith("///", i) -> throw IllegalArgumentException(
                    "Too many consecutive slashes at position $i in XPath: '$s'.",
                )
                s.startsWith("//", i) -> { i += 2; Axis.DESCENDANT_OR_SELF }
                s.startsWith("/", i) -> { i += 1; Axis.CHILD }
                else -> Axis.CHILD
            }
            val nameStart = i
            while (i < s.length && s[i] != '/' && s[i] != '[') i++
            // Trim allows tolerant locators like `// ActionButton`; an empty name becomes
            // the wildcard `*` so `/foo/` and `//` keep working.
            val tagName = s.substring(nameStart, i).trim().ifEmpty { "*" }

            if (tagName == "..") {
                throw IllegalArgumentException(
                    "Parent axis '..' is not supported in XPath: '$s'.",
                )
            }

            val predicates = mutableListOf<Pair<String, String>>()
            var positional: Int? = null
            while (i < s.length && s[i] == '[') {
                val end = findMatchingBracket(s, i)
                require(end > i) { "Unbalanced predicate at $i in '$s'." }
                val raw = s.substring(i + 1, end).trim()
                if (raw.isEmpty()) {
                    throw IllegalArgumentException("Empty predicate '[]' in XPath: '$s'.")
                }
                val asInt = raw.toIntOrNull()
                if (asInt != null) {
                    if (positional != null) {
                        throw IllegalArgumentException(
                            "Multiple positional predicates in one step: '$s'.",
                        )
                    }
                    if (asInt < 1) {
                        throw IllegalArgumentException(
                            "Position must be >= 1, got $asInt in XPath: '$s'.",
                        )
                    }
                    positional = asInt
                } else {
                    if (containsTopLevelOr(raw)) {
                        throw IllegalArgumentException(
                            "'or' is not supported in XPath predicates: '$raw'. " +
                                "Run separate queries and merge the results instead.",
                        )
                    }
                    val parts = splitByAnd(raw)
                    for (p in parts) predicates += parsePredicate(p)
                }
                i = end + 1
            }

            if (tagName == ".") {
                // `.` is the self axis. Predicates still apply (`.[@class='X']` keeps the
                // context node only when it matches). Treat its name as wildcard so the
                // name check in matchesNameAndPredicates doesn't reject every node.
                steps += Step(Axis.SELF, "*", predicates, positional)
            } else {
                steps += Step(axis, tagName, predicates, positional)
            }
        }
        return steps
    }

    private fun findMatchingBracket(s: String, openIdx: Int): Int {
        var depth = 0
        var inSingle = false
        var inDouble = false
        var i = openIdx
        while (i < s.length) {
            val c = s[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '[' -> depth++
                c == ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun containsTopLevelOr(s: String): Boolean {
        val lower = s.lowercase()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                !inSingle && !inDouble &&
                    i + 3 < s.length && lower.startsWith(" or ", i) -> return true
            }
            i++
        }
        return false
    }

    private fun splitByAnd(s: String): List<String> {
        val out = mutableListOf<String>()
        val lower = s.lowercase()
        var start = 0
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                !inSingle && !inDouble &&
                    i + 4 < s.length && lower.startsWith(" and ", i) -> {
                    out.add(s.substring(start, i).trim())
                    start = i + 5
                    i += 4
                }
            }
            i++
        }
        out.add(s.substring(start).trim())
        return out.filter { it.isNotEmpty() }
    }

    private fun parsePredicate(raw: String): Pair<String, String> {
        // Expected forms: @attr='value' / @attr="value" / @attr=value (lenient).
        // Every bad shape throws — silent drop would make missing-@ typos invisible.
        val r = raw.trim()
        if (!r.startsWith("@")) {
            throw IllegalArgumentException(
                "Unsupported predicate '$r'. " +
                    "Predicates must be '@attr=\"value\"' (chain with ' and ') " +
                    "or positional '[N]'. Function predicates like text() or " +
                    "contains() are not part of the supported subset.",
            )
        }
        val eq = r.indexOf('=')
        if (eq < 0) {
            throw IllegalArgumentException("Predicate '$r' is missing '=value'.")
        }
        val attr = r.substring(1, eq).trim()
        if (attr.isEmpty()) {
            throw IllegalArgumentException("Predicate '$r' has empty attribute name.")
        }
        var value = r.substring(eq + 1).trim()
        val startsQuote = value.firstOrNull() == '\'' || value.firstOrNull() == '"'
        val endsQuote = value.lastOrNull() == '\'' || value.lastOrNull() == '"'
        val sameQuote = (value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\""))
        if ((startsQuote || endsQuote) && !sameQuote) {
            throw IllegalArgumentException(
                "Mismatched quotes in predicate value '$value'.",
            )
        }
        if (sameQuote && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        return attr to value
    }
}
