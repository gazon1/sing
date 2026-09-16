package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Type alias for the `actions` slot in [EmptyState] — allows callers to pass
 * trailing action buttons as a composable lambda with [ColumnScope] access.
 */
typealias EmptyStateActions = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit

/**
 * Centered "nothing here" placeholder used by empty list branches.
 *
 * @param title    Primary message displayed in [MaterialTheme.typography.titleMedium].
 * @param subtitle Optional secondary message in [MaterialTheme.typography.bodyMedium].
 * @param actions  Optional composable slot — e.g. a CTA button. Defaults to empty.
 *                 Placed below the subtitle with [Spacer] spacing.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: EmptyStateActions = {},
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
            if (actions !== {}) {
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

/**
 * Conditional composable with else branch — renders [ifTrue] when [condition] is true,
 * [ifFalse] when false.
 */
@Composable
inline fun IfElse(condition: Boolean, ifTrue: @Composable () -> Unit, ifFalse: @Composable () -> Unit) {
    if (condition) ifTrue() else ifFalse()
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

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun EmptyStateWithActionsPreview() = PreviewThemed(darkTheme = false) {
    EmptyState(
        title = "No notes yet",
        subtitle = "Create your first note to get started",
        actions = {
            androidx.compose.material3.FilledTonalButton(
                onClick = {},
            ) {
                Text("Create your first note")
            }
        },
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun EmptyStateWithActionsDarkPreview() = PreviewThemed(darkTheme = true) {
    EmptyState(
        title = "No tasks yet",
        subtitle = "Tap + to create one",
        actions = {
            androidx.compose.material3.FilledTonalButton(
                onClick = {},
            ) {
                Text("Create task")
            }
        },
    )
}
