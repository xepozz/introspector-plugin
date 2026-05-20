package com.github.xepozz.introspectorplugin.exec

import com.intellij.openapi.diagnostic.Logger
import java.time.Instant

/**
 * Audit trail for executed Kotlin: writes one structured line per execution to the IDE
 * logger (visible in idea.log under category 'ide-introspect-mcp-audit').
 */
object AuditLogger {
    private val log = Logger.getInstance("ide-introspect-mcp-audit")

    fun record(code: String, outcome: String, durationMs: Long, resultPreview: String?) {
        if (!ExecSettings.getInstance().auditEnabled) return
        val ts = Instant.now().toString()
        val codeOneLine = code.replace("\n", " \\n ").take(500)
        val previewOneLine = resultPreview?.replace("\n", " \\n ")?.take(500)
        log.info("[$ts] outcome=$outcome durationMs=$durationMs code=`$codeOneLine` result=`${previewOneLine ?: ""}`")
    }
}
