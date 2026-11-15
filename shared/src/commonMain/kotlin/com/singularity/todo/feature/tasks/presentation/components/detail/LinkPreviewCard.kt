package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.singularity.todo.core.linkpreview.LinkPreview
import com.singularity.todo.core.linkpreview.LinkPreviewState
import com.singularity.todo.core.linkpreview.PreviewUrls
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * The link preview under the task's title, as Telegram draws one.
 *
 * ## What it always is
 *
 * A compact card: the site's name, the page's title, a short description and — when
 * there is one — a picture. Every field but the title is optional, and a page that gave
 * only a title is still a card. The URL itself is never shown here: it is already in the
 * title above, and printing it twice is noise.
 *
 * ## What it is when there is nothing to show
 *
 * Nothing at all. [LinkPreviewState.None] and [LinkPreviewState.Unavailable] draw no
 * node: a failed fetch is not a broken card, and an address that could not be read is
 * still tappable in the title above. [LinkPreviewState.Loading] is a thin progress line
 * rather than an empty box, so the space a card will take does not appear and then
 * shift.
 *
 * ## The picture
 *
 * Loaded from the local file the cache wrote, never from the remote address: the page
 * chose that address, and the app fetched it through the guarded path. A file that is
 * missing or will not decode leaves the text — the card is text first.
 *
 * @param state what the slot holds for the title's address.
 * @param imagePath the local file the preview image was stored at, or null.
 * @param onOpen opens the address outside the app.
 * @param onHide dismisses the card for this address. The title is untouched.
 */
@Composable
fun LinkPreviewCard(
    state: LinkPreviewState,
    imagePath: String?,
    onOpen: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LinkPreviewState.None, LinkPreviewState.Unavailable, LinkPreviewState.Hidden -> {
            Unit
        }

        LinkPreviewState.Loading -> {
            LinearProgressIndicator(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(top = TaskSpacing.sm)
                        .testTag(TestTags.TASK_LINK_PREVIEW_LOADING),
            )
        }

        is LinkPreviewState.Ready -> {
            ReadyCard(
                preview = state.preview,
                imagePath = imagePath,
                onOpen = onOpen,
                onHide = onHide,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ReadyCard(
    preview: LinkPreview,
    imagePath: String?,
    onOpen: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = TaskSpacing.sm)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen)
                .testTag(TestTags.TASK_LINK_PREVIEW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(TaskSpacing.sm),
        ) {
            val site = preview.siteName ?: PreviewUrls.host(preview.url)
            if (site.isNotBlank()) {
                Text(
                    text = site,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TestTags.TASK_LINK_PREVIEW_SITE),
                )
            }
            Text(
                text = preview.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TestTags.TASK_LINK_PREVIEW_TITLE),
            )
            preview.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TestTags.TASK_LINK_PREVIEW_DESCRIPTION),
                )
            }
        }

        if (imagePath != null) {
            AsyncImage(
                model = imagePath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(72.dp)
                        .padding(end = TaskSpacing.sm)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag(TestTags.TASK_LINK_PREVIEW_IMAGE),
            )
        }

        IconButton(
            onClick = onHide,
            modifier = Modifier.touchTarget().testTag(TestTags.TASK_LINK_PREVIEW_HIDE),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Hide preview",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
