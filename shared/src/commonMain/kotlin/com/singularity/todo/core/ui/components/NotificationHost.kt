package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.singularity.todo.core.ui.preview.PreviewThemed
import kotlinx.coroutines.flow.Flow

/**
 * Generic one-shot notification overlay.
 *
 * Collects events from [events] flow, maps each through [mapper] to a [Notification],
 * and renders the appropriate transient UI (dialog or navigation).
 *
 * Replaces the manual `var dialogText by remember { mutableStateOf<String?>(null) }`
 * + `CollectEvents { ... when (event) { ... } }` + `ResultDialog` boilerplate
 * that was copy-pasted across 8 screens.
 *
 * @param events     The feature-specific event flow (e.g. `Flow<TasksUiEvent>`).
 * @param mapper     Pure function that translates a feature event into a [Notification].
 *                   Keeping it as a lambda lets each screen keep its formatting logic
 *                   without leaking domain types into this widget.
 * @param onNavigateBack Called when [Notification.NavigateBack] is mapped.
 * @param modifier   Standard Compose modifier.
 */
@Composable
fun <T> NotificationHost(
    events: Flow<T>,
    mapper: (T) -> Notification,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var notification by remember { mutableStateOf<Notification?>(null) }

    CollectEvents(events) { event ->
        notification = mapper(event)
    }

    when (val n = notification) {
        is Notification.Text -> {
            ResultDialog(
                title = n.title,
                text = n.text,
                onDismiss = { notification = null },
            )
        }

        is Notification.Error -> {
            ResultDialog(
                title = "Error",
                text = n.message,
                onDismiss = { notification = null },
            )
        }

        Notification.NavigateBack -> {
            notification = null
            onNavigateBack?.invoke()
        }

        Notification.Dismiss -> {
            notification = null
        }

        Notification.None -> { /* handled via other UI (e.g. animation) */ }

        null -> { /* nothing to show */ }
    }
}

// ===== Preview =====
// NotificationHost itself requires a Flow<T> and CollectEvents, so we preview
// the individual dialog outcomes that it can produce via ResultDialog.

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotificationHostTextDialogPreview() = PreviewThemed(darkTheme = false) {
    ResultDialog(
        title = "Task saved",
        text = "Your changes have been saved successfully.",
        onDismiss = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotificationHostErrorDialogDarkPreview() = PreviewThemed(darkTheme = true) {
    ResultDialog(
        title = "Error",
        text = "Failed to save task. Please try again.",
        onDismiss = {},
    )
}
