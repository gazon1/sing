package com.singularity.todo.core.linkpreview

/**
 * What a link preview says about a page.
 *
 * Metadata only: the fields a page publishes about itself, or the ones this app
 * could read off the page's own text. There is no summary field, because there is
 * no summariser — the preview is what the page says about itself and nothing this
 * app inferred (#2953 explicitly makes AI summarisation opt-in and out of this
 * change's default path).
 *
 * @property url the address the preview is about, as the cache keys it.
 * @property siteName the publisher's own name for itself, when the page states one.
 * @property title the page's title, or the address itself when the page has none.
 * @property description the page's own description, or null.
 * @property imageUrl the preview image, resolved against the final page address.
 * @property fetchedAt when the metadata was read. The cache uses it for expiry.
 */
data class LinkPreview(
    val url: String,
    val siteName: String?,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val fetchedAt: Long,
) {
    /**
     * What to call the page in one string.
     *
     * The title when the page gave one, the site name when it only gave that, and
     * the host otherwise — from the address, which is always there.
     */
    val displayTitle: String get() = title.ifBlank { siteName ?: PreviewUrls.host(url) }
}

/** A preview as the screen shows it. */
sealed interface LinkPreviewState {
    /** Nothing to preview — the title carries no supported address. */
    data object None : LinkPreviewState

    /** The metadata is being read. */
    data object Loading : LinkPreviewState

    /** The page answered, or the cache held a previous answer. */
    data class Ready(val preview: LinkPreview) : LinkPreviewState

    /**
     * The page could not be read.
     *
     * Not an error message: the address in the title still works, and the card
     * for a failed read is a quiet line rather than a broken one.
     */
    data object Unavailable : LinkPreviewState

    /** The user dismissed the preview for this address. */
    data object Hidden : LinkPreviewState
}
