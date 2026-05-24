package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

@Serializable
data class LogTailArgs(
    val lines: Int = 200,
    val categoryContains: String? = null,
    val severity: String? = null,
    val regex: String? = null,
    val maxBytes: Int = 65_536,
)

@Serializable
data class LogErrorsSinceArgs(
    val sinceIsoTimestamp: String? = null,
    val lastMinutes: Int? = 30,
    val minSeverity: String = "WARN",
    val limit: Int = 100,
    val groupByThrowable: Boolean = true,
    val maxBytes: Int = 131_072,
)
