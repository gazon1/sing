package com.singularity.todo.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick

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

/**
 * Open-lock button: the way back for something that was closed.
 *
 * A closed project is finished, not gone — and the only way back is this
 * button. A closed project without one is a project the user can never get
 * back.
 */
@Composable
fun ReopenActionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Filled.LockOpen,
            contentDescription = "Reopen",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AiActionButtonLightPreview() = PreviewThemed(darkTheme = false) {
    AiActionButton(onClick = noopClick)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AiActionButtonDarkPreview() = PreviewThemed(darkTheme = true) {
    AiActionButton(onClick = noopClick)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun DeleteActionButtonLightPreview() = PreviewThemed(darkTheme = false) {
    DeleteActionButton(onClick = noopClick)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun DeleteActionButtonDarkPreview() = PreviewThemed(darkTheme = true) {
    DeleteActionButton(onClick = noopClick)
}
