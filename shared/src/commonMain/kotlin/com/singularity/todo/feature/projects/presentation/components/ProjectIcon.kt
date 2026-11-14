package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ui.TestTags

/**
 * Renders a project's icon: the stored emoji, or a folder when none is set.
 *
 * Pass a null [contentDescription] when the row already names the project.
 */
@Composable
fun ProjectIcon(
    icon: Emoji?,
    tint: Color,
    size: Dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val emoji = icon?.value?.takeIf { it.isNotBlank() }
    if (emoji == null) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(size).testTag(TestTags.PROJECT_ICON_FALLBACK),
        )
        return
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            color = tint,
            fontSize = (size.value * 0.75f).sp,
            textAlign = TextAlign.Center,
            // A composed sequence — ZWJ, flag, skin tone, variation
            // selector — is one grapheme and must stay one line; wrapping
            // or ellipsizing would split a single glyph.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
