package com.github.xepozz.introspectorplugin.exec

/**
 * Lightweight textual blacklist applied before compilation. NOT a security boundary in
 * the cryptographic sense — anybody with arbitrary Kotlin can defeat it through reflection
 * tricks — but it raises the bar enough to stop accidental damage from prompt injection.
 *
 * Pairs with per-call confirmation and the global opt-in toggle.
 */
object AstSafetyChecker {

    private val FORBIDDEN_PATTERNS = listOf(
        Regex("""Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\("""),
        Regex("""ProcessBuilder\s*\("""),
        Regex("""setAccessible\s*\(\s*true\s*\)"""),
        Regex("""System\s*\.\s*exit\s*\("""),
        Regex("""Class\s*\.\s*forName\s*\(\s*"sun\."""),
    )

    data class CheckResult(val ok: Boolean, val reason: String? = null)

    fun check(code: String): CheckResult {
        for (re in FORBIDDEN_PATTERNS) {
            val m = re.find(code) ?: continue
            return CheckResult(false, "Forbidden API in user code: '${m.value}' (matched pattern ${re.pattern})")
        }
        return CheckResult(true)
    }
}
