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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * Renders a project's stored icon string.
 *
 * Resolution (registered key vs. free-form emoji vs. fallback) lives in
 * [resolveProjectIcon], so this composable only draws.
 *
 * This is the project-identity contract every surface draws through: the menu
 * tree, the project card and the task Project Picker all pass the stored
 * `Project.icon` here, so one project shows one glyph wherever it appears.
 * Surfaces choose their own [size] and [tint]; the resolution and the fallback
 * do not vary.
 *
 * Pass a null [contentDescription] when the row already names the project: a
 * decorative icon must not contribute a second accessible name.
 */
@Composable
fun ProjectIcon(
    icon: String?,
    tint: Color,
    size: Dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    when (val resolved = resolveProjectIcon(icon)) {
        is ResolvedProjectIcon.Material -> {
            Icon(
                imageVector =
                    com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry
                        .iconByKey(resolved.key)!!,
                contentDescription = contentDescription,
                tint = tint,
                modifier = modifier.size(size),
            )
        }

        is ResolvedProjectIcon.Emoji -> {
            Box(
                modifier = modifier.size(size),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = resolved.value,
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

        ResolvedProjectIcon.None -> {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = contentDescription,
                tint = tint,
                modifier = modifier.size(size),
            )
        }
    }
}
