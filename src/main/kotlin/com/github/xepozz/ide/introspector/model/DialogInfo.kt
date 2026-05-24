package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Lightweight semantic descriptor of one open dialog window. See [DialogsResponse].
 *
 * NOTE: Placeholder shape kept to unblock compilation while the `ui-semantic-listing`
 * feature is still in flight — see `docs/plans/ui-semantic-listing.md`.
 */
@Serializable
data class DialogInfo(
    val componentId: String,
    val title: String? = null,
    val bounds: Bounds? = null,
    val modal: Boolean = false,
    val resizable: Boolean = false,
    val contentClass: String? = null,
)

@Serializable
data class DialogsResponse(
    val dialogs: List<DialogInfo> = emptyList(),
    val warnings: List<String> = emptyList(),
)
