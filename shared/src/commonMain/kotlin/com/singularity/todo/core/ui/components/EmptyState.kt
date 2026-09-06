package com.singularity.todo.core.ui.components

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Centered "nothing here" placeholder used by empty list branches.
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun EmptyStateLightPreview() = PreviewThemed(darkTheme = false) {
    EmptyState(title = "No tasks yet", subtitle = "Tap + to create one")
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun EmptyStateDarkPreview() = PreviewThemed(darkTheme = true) {
    EmptyState(title = "No results", subtitle = "Try a different search")
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun EmptyStateNoSubtitlePreview() = PreviewThemed(darkTheme = false) {
    EmptyState(title = "Nothing here")
}
