package com.github.xepozz.introspectorplugin.model.args

import kotlinx.serialization.Serializable

@Serializable
data class ExecuteKotlinArgs(
    val code: String,
    val timeoutMs: Long = 30_000,
    val captureStdout: Boolean = true,
    val captureStderr: Boolean = true,
    val runOn: String = "edt",               // "edt" | "background" | "auto"
)
