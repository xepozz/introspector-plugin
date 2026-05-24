package com.github.xepozz.ide.introspector.exec

import com.intellij.openapi.diagnostic.Logger
import java.time.Instant

/**
 * Audit trail for executed Kotlin: writes one structured line per execution to the IDE
 * logger (visible in idea.log under category 'ide-introspector-audit').
 */
object AuditLogger {
    private val log = Logger.getInstance("ide-introspector-audit")

    fun record(code: String, outcome: String, durationMs: Long, resultPreview: String?) {
        if (!ExecSettings.getInstance().auditEnabled) return
        val ts = Instant.now().toString()
        val codeOneLine = code.replace("\n", " \\n ").take(500)
        val previewOneLine = resultPreview?.replace("\n", " \\n ")?.take(500)
        log.info("[$ts] outcome=$outcome durationMs=$durationMs code=`$codeOneLine` result=`${previewOneLine ?: ""}`")
    }

    /**
     * Audit overload for `ui.invoke_action_on`. Same single-line, grep-friendly format as
     * [record] above, prefixed with `[ui.invoke_action_on]` so callers can distinguish
     * action invocations from Kotlin exec calls in idea.log.
     *
     * Honours [UiActionSettings.auditEnabled] independently of [ExecSettings.auditEnabled].
     */
    fun recordUiAction(
        actionId: String,
        componentId: String,
        componentClass: String?,
        outcome: String,
        durationMs: Long,
        error: String? = null,
    ) {
        if (!UiActionSettings.getInstance().auditEnabled) return
        val ts = Instant.now().toString()
        val errorOneLine = error?.replace("\n", " \\n ")?.take(500)
        log.info(
            "[ui.invoke_action_on] [$ts] outcome=$outcome durationMs=$durationMs " +
                "actionId=`$actionId` componentId=$componentId componentClass=${componentClass ?: "(unknown)"} " +
                "error=`${errorOneLine ?: ""}`"
        )
    }
}
