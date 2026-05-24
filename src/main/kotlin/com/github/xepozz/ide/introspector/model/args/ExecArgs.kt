package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

@Serializable
data class ExecuteKotlinArgs(
    val code: String,
    val timeoutMs: Long = 10_000,
    val captureStdout: Boolean = true,
    val captureStderr: Boolean = true,
    val runOn: String = "edt",               // "edt" | "background" | "auto"
)

@Serializable
data class CompileCheckArgs(
    val code: String,
    val wrap: Boolean = true,
)
