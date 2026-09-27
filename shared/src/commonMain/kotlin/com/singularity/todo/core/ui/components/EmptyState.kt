package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Type alias for the `actions` slot in [EmptyState] — allows callers to pass
 * trailing action buttons as a composable lambda with [ColumnScope] access.
 */
typealias EmptyStateActions = @Composable ColumnScope.() -> Unit

/**
 * Centered "nothing here" placeholder used by empty list branches.
 *
 * @param title    Primary message displayed in [MaterialTheme.typography.titleMedium].
 * @param subtitle Optional secondary message in [MaterialTheme.typography.bodyMedium].
 * @param actions  Optional composable slot — e.g. a CTA button. `null` (the default)
 *   renders no action and no extra spacing below the subtitle. Placed below the
 *   subtitle with [Spacer] spacing when non-null.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: EmptyStateActions? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            // `actions != null` is a real presence check, unlike the previous
            // `actions !== {}`: that compared two distinct lambda objects by
            // reference and was true unconditionally, so the 24.dp spacer
            // rendered even when the caller passed no actions at all.
            if (actions != null) {
                Spacer(Modifier.height(24.dp))
                actions()
            }
        }
    }
}

/**
 * Conditional composable — renders [content] when [condition] is true, otherwise renders nothing.
 * Use instead of `if (cond) { content() }` at the top level of a Composable body.
 */
@Composable
inline fun If(condition: Boolean, content: @Composable () -> Unit) {
    if (condition) content()
}

@Preview
@Composable
private fun EmptyStateLightPreview() = PreviewThemed(darkTheme = false) {
    EmptyState(title = "No tasks yet", subtitle = "Tap + to create one")
}

@Preview
@Composable
private fun EmptyStateDarkPreview() = PreviewThemed(darkTheme = true) {
    EmptyState(title = "No results", subtitle = "Try a different search")
}

@Preview
@Composable
private fun EmptyStateNoSubtitlePreview() = PreviewThemed(darkTheme = false) {
    EmptyState(title = "Nothing here")
}

@Preview
@Composable
private fun EmptyStateWithActionsPreview() = PreviewThemed(darkTheme = false) {
    EmptyState(
        title = "No notes yet",
        subtitle = "Create your first note to get started",
        actions = {
            FilledTonalButton(onClick = {}) {
                Text("Create your first note")
            }
        },
    )
}

@Preview
@Composable
private fun EmptyStateWithActionsDarkPreview() = PreviewThemed(darkTheme = true) {
    EmptyState(
        title = "No tasks yet",
        subtitle = "Tap + to create one",
        actions = {
            FilledTonalButton(onClick = {}) {
                Text("Create task")
            }
        },
    )
}

@Preview
@Composable
private fun LoadingIndicatorLightPreview() = PreviewThemed(darkTheme = false) {
    LoadingIndicator()
}

@Preview
@Composable
private fun LoadingIndicatorDarkPreview() = PreviewThemed(darkTheme = true) {
    LoadingIndicator()
}
