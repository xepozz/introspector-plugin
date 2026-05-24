package com.github.xepozz.ide.introspector.exec

/**
 * Action-id blocklist for `ui.invoke_action_on`.
 *
 * Triggers a SECOND confirmation prompt (no session bypass available) for action ids that
 * are known-destructive — force pushes, deletes, resets, hard refactor reverts, etc. The
 * list is matched against the union of:
 *
 *  1. [DEFAULT_IDS] — known dangerous IntelliJ action ids (exact match).
 *  2. [DEFAULT_PATTERNS] — wildcard patterns (`*Force*`, `*Delete*`, `*Reset*`) that catch
 *     plugin actions whose ids follow the same convention.
 *  3. The user-extendable list from [UiActionSettings.blocklistedActionIds] (each entry
 *     can be either an exact id or a wildcard pattern using `*`).
 *
 * The blocklist is intentionally over-inclusive: a confirmation prompt is annoying, but
 * a force-pushed branch you didn't approve is much worse.
 */
object UiActionBlocklist {

    /** Exact-match dangerous action ids shipped with IntelliJ / common plugins. */
    val DEFAULT_IDS: List<String> = listOf(
        "Vcs.RefactoringChanges",
        "Reset_HEAD",
        "Maven.Reimport",
        "Git.Reset",
        "Git.Reset.In.Log",
        "Vcs.Push.Force",
        "Vcs.Force.Push",
        "Compare.SameVersion",
        "Refactorings.QuickListPopupAction",
    )

    /** Glob-style patterns (only `*` supported). Case-INSENSITIVE match against the id. */
    val DEFAULT_PATTERNS: List<String> = listOf(
        "*Force*",
        "*Delete*",
        "*Reset*",
    )

    /**
     * Returns true if [actionId] matches either the union of [DEFAULT_IDS] +
     * [userExtraIds] (exact match, case-sensitive) OR any of
     * [DEFAULT_PATTERNS] + [userExtraIds]-treated-as-patterns (case-insensitive).
     *
     * User-supplied entries are tested both ways: a literal id like `My.Force.Action`
     * matches as an exact id, and an entry like `*Force*` matches as a wildcard. The
     * cost of double-testing is negligible (string comparison) and avoids forcing the
     * user to know which list their entry belongs to.
     */
    fun matches(actionId: String, userExtraIds: List<String> = emptyList()): Boolean {
        if (actionId.isEmpty()) return false
        // 1. Exact-id match against defaults + user extras.
        if (actionId in DEFAULT_IDS) return true
        if (actionId in userExtraIds) return true
        // 2. Wildcard match against defaults + user extras.
        val patterns = DEFAULT_PATTERNS + userExtraIds
        for (p in patterns) {
            if (matchesGlob(actionId, p)) return true
        }
        return false
    }

    /**
     * Tiny glob matcher — only `*` is special (matches any sequence including empty).
     * Case-insensitive, as the action-id convention varies between plugins.
     */
    internal fun matchesGlob(input: String, pattern: String): Boolean {
        if (!pattern.contains('*')) return false
        val regex = buildString {
            append('^')
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    in REGEX_SPECIALS -> { append('\\'); append(ch) }
                    else -> append(ch)
                }
            }
            append('$')
        }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(input)
    }

    private val REGEX_SPECIALS = setOf('.', '+', '?', '(', ')', '[', ']', '{', '}', '|', '^', '$', '\\')
}
