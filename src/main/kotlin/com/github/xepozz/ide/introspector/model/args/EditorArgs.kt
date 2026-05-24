package com.github.xepozz.ide.introspector.model.args

import kotlinx.serialization.Serializable

@Serializable
data class SetCaretArgs(
    val fileUrl: String? = null,
    val offset: Int? = null,
    val line: Int? = null,
    val column: Int = 1,
    val scrollToVisible: Boolean = true,
)

@Serializable
data class GetStateArgs(
    val fileUrl: String? = null,
    val includeMultipleCarets: Boolean = true,
    val includeFolding: Boolean = true,
    val includeInlays: Boolean = false,
    val gutterMinSeverity: String = "WARNING",
)
