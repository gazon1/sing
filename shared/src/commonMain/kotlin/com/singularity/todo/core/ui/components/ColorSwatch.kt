package com.singularity.todo.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .semantics {
                this.contentDescription = if (selected) "$name, selected" else name
                role = Role.Button
            }
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        content()
    }
}
