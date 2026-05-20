package com.github.xepozz.introspectorplugin.core

import com.github.xepozz.introspectorplugin.model.ComponentInfo

/**
 * Tiny XPath-like matcher operating on already-serialised [ComponentInfo] trees so we never
 * have to bounce on the EDT during evaluation. Supports the subset used by
 * `intellij-ui-test-robot`:
 *
 *  - `/foo`  — child step
 *  - `//foo` — descendant-or-self step
 *  - `.`     — self
 *  - `*`     — any element
 *  - `div`   — wildcard alias used by remote-robot ("any component")
 *  - predicates `[@attr='value']` or `[@attr=\"value\"]` with `and`-joined predicates
 *  - positional predicate `[N]` (1-based)
 *
 * Supported attributes: `@class`, `@name`, `@accessibleName`, `@text`, `@toolTipText`.
 * Predicates currently only test equality.
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
        val stack: ArrayDeque<ComponentInfo> = ArrayDeque()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            out.add(n)
            for (cid in n.children) {
                val c = nodesById[cid] ?: continue
                stack.addLast(c)
            }
        }
    }

    private fun matchesNameAndPredicates(node: ComponentInfo, step: Step): Boolean {
        if (step.localName != "*" && step.localName != "div") {
            // localName matches against the simple class name.
            val simple = node.className.substringAfterLast('.')
            if (!simple.equals(step.localName, ignoreCase = false) &&
                !node.className.equals(step.localName, ignoreCase = false)
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
                else -> return false
            }
            if (actual != expected) return false
        }
        return true
    }

    // ---------------- Parser ----------------

    private enum class Axis { CHILD, DESCENDANT_OR_SELF, SELF }

    private data class Step(
        val axis: Axis,
        val localName: String,
        val predicates: List<Pair<String, String>>,
        val positional: Int?,
    )

    private fun parseSteps(input: String): List<Step> {
        val s = input.trim()
        val steps = mutableListOf<Step>()
        var i = 0
        // Leading `/` or `//` defines whether to start absolute.
        // We treat them like normal step prefixes.
        // If the input doesn't start with `/`, we treat it as descendant-or-self.
        if (!s.startsWith("/")) {
            return parseSingleChain("//$s")
        }
        return parseSingleChain(s)
    }

    private fun parseSingleChain(s: String): List<Step> {
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < s.length) {
            val axis: Axis = when {
                s.startsWith("//", i) -> { i += 2; Axis.DESCENDANT_OR_SELF }
                s.startsWith("/", i) -> { i += 1; Axis.CHILD }
                else -> Axis.CHILD
            }
            val nameStart = i
            while (i < s.length && s[i] != '/' && s[i] != '[') i++
            val localName = s.substring(nameStart, i).ifEmpty { "*" }

            val predicates = mutableListOf<Pair<String, String>>()
            var positional: Int? = null
            while (i < s.length && s[i] == '[') {
                val end = findMatchingBracket(s, i)
                require(end > i) { "Unbalanced predicate at $i in $s" }
                val raw = s.substring(i + 1, end).trim()
                if (raw.toIntOrNull() != null) {
                    positional = raw.toInt()
                } else {
                    // Split by " and " (case-insensitive, surrounded by whitespace).
                    val parts = splitByAnd(raw)
                    for (p in parts) parsePredicate(p)?.let(predicates::add)
                }
                i = end + 1
            }

            if (localName == "." && predicates.isEmpty()) {
                steps += Step(Axis.SELF, "*", emptyList(), null)
            } else {
                steps += Step(axis, localName, predicates, positional)
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

    private fun parsePredicate(raw: String): Pair<String, String>? {
        // Expected forms: @attr='value' / @attr="value" / @attr=value
        val r = raw.trim()
        if (!r.startsWith("@")) return null
        val eq = r.indexOf('=')
        if (eq < 0) return null
        val attr = r.substring(1, eq).trim()
        var value = r.substring(eq + 1).trim()
        if ((value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\""))
        ) {
            value = value.substring(1, value.length - 1)
        }
        return attr to value
    }
}
