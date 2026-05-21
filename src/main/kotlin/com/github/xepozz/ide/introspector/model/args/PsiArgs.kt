package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

@Serializable
data class GetPsiStructureArgs(
    val fileUrl: String? = null,
    val maxDepth: Int = 20,
    val maxNodes: Int = 5_000,
    val includeWhitespace: Boolean = false,
    val includeText: Boolean = false,
    val truncateNodeTextAt: Int = 80,
    val includeInjections: Boolean = true,
)

@Serializable
data class GetReferencesArgs(
    val fileUrl: String? = null,
    val offset: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    val scope: String = "file",                // "at_offset" | "file"
    val includeMultiResolve: Boolean = true,
    val maxReferences: Int = 1_000,
    val truncateTextAt: Int = 80,
)

@Serializable
data class FindUsagesArgs(
    val fileUrl: String? = null,
    val offset: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    val scope: String = "project",             // "project" | "file" | "all"
    val includeImplementations: Boolean = true,
    val maxUsages: Int = 500,
    val truncateTextAt: Int = 120,
    val groupByFile: Boolean = false,
)
