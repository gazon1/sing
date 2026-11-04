package com.singularity.todo.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.contrastingForeground
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes

/**
 * A round colour button.
 *
 * Its content is drawn in [contentColor], which by default is the foreground that
 * stays readable on [color]. A caller that draws a check inside the swatch
 * therefore does not have to name a tint of its own.
 */
@Composable
fun ColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    contentColor: Color = color.contrastingForeground(),
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .touchTarget(TaskListSizes.MinTouchTarget)
                .size(size)
                .clip(CircleShape)
                .background(color)
                .semantics {
                    this.contentDescription = if (selected) "$name, selected" else name
                    role = Role.Button
                }.then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier,
                ).clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
