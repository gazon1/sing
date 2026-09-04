package com.singularity.todo.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Sparkle-icon button used everywhere an AI capability is offered
 * (TaskCard, ProjectCard, NoteEditor toolbar).
 */
@Composable
fun AiActionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = "AI actions",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Trash-icon button used by every deletable list item. */
@Composable
fun DeleteActionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Delete",
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
