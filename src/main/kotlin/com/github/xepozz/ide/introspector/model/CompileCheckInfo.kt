package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Result of `exec.compile_check`. `ok` is true iff there are zero ERROR/FATAL diagnostics
 * (warnings/info do not flip `ok`).
 */
@Serializable
data class CompileCheckResponse(
    val ok: Boolean,
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    val warnings: List<String> = emptyList(),
    val durationMs: Long,
)

/**
 * A single compiler diagnostic. `severity` mirrors the Kotlin scripting
 * `ScriptDiagnostic.Severity` enum: FATAL | ERROR | WARNING | INFO | DEBUG.
 * `line`/`column` are 1-based and refer to the (wrapped) script's coordinate system.
 */
@Serializable
data class CompileDiagnostic(
    val severity: String,
    val line: Int? = null,
    val column: Int? = null,
    val file: String? = null,
    val message: String,
    val factoryId: String? = null,
)
