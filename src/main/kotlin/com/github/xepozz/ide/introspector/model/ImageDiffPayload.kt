package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Response for `screenshot.diff`. Parallels
 * [com.github.xepozz.ide.introspector.tools.ImagePayload] field-for-field — kotlinx
 * @Serializable data classes don't cleanly extend, and the CLAUDE.md classloader policy
 * for serialization keeps us from sharing a sealed hierarchy across the model boundary.
 *
 * @property bbox the smallest axis-aligned rectangle covering every differing pixel in
 *   the **returned composite's** coordinate space (post-downscale if a budget warning is
 *   present). `null` when [differingPixels] == 0.
 */
@Serializable
data class ImageDiffPayload(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val base64: String,
    val warnings: List<String> = emptyList(),
    val totalPixels: Int,
    val differingPixels: Int,
    val diffPercentage: Double,
    val bbox: Bounds? = null,
)
