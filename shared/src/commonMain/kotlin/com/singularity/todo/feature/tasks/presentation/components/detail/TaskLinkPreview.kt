package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.singularity.todo.core.linkpreview.LinkPreviewCache
import com.singularity.todo.core.linkpreview.LinkPreviewState
import com.singularity.todo.core.linkpreview.PreviewUrls
import com.singularity.todo.core.ui.text.rememberAppUriHandler
import org.koin.compose.koinInject

/**
 * The link preview under the title, wired to the app's own address handling.
 *
 * ## Why the address is resolved here and not in the card
 *
 * The card draws what it is given; opening an address is the app's business, and the
 * app already has one way to do it — [rememberAppUriHandler], which sends a web address
 * out of the app and an internal one where it belongs. A card that called the platform
 * URI handler directly would be a second path that could drift.
 *
 * ## The local image
 *
 * The picture was downloaded through the guarded fetcher and stored on disk, so the card
 * is given a local path. It never points the image loader at the remote address: that
 * address came from the page, and letting the loader request it would bypass the SSRF
 * check the page itself went through. The path is derived from the address, so it is
 * stable across recomposition and needs no lookup.
 *
 * @param state what the slot holds.
 * @param onHide dismisses the card for the address.
 */
@Composable
fun TaskLinkPreview(
    state: LinkPreviewState,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = rememberAppUriHandler()
    val cache: LinkPreviewCache = koinInject()
    val ready = state as? LinkPreviewState.Ready
    // The image belongs to the address the *page* named, and a refresh may name a
    // different one. The path is therefore keyed by both: a picture left over from the
    // previous entry is a picture of the wrong page, and the file is deleted when the
    // entry changes — so the card must be told about the new address, not the old one.
    val imagePath =
        remember(ready?.preview?.url, ready?.preview?.imageUrl) {
            val imageUrl = ready?.preview?.imageUrl ?: return@remember null
            ready.preview.url
                .takeIf { imageUrl.isNotBlank() }
                ?.let { cache.imagePath(PreviewUrls.keyOf(it)) }
        }

    LinkPreviewCard(
        state = state,
        imagePath = imagePath,
        onOpen = {
            val url = (state as? LinkPreviewState.Ready)?.preview?.url ?: return@LinkPreviewCard
            uriHandler.openUri(url)
        },
        onHide = onHide,
        modifier = modifier,
    )
}
