package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * @param events     The feature-specific event flow (e.g. `Flow<TaskDetailUiEvent>`).
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
    val snackbarHostState = remember { SnackbarHostState() }

    CollectEvents(events) { event ->
        notification = mapper(event)
    }

    // Present the undo toast and clear the queued notification. `showSnackbar`
    // suspends until the toast is dismissed, so one effect both presents it and
    // keeps the next event from stacking behind it. Taking the result here — rather
    // than from a button callback — means a swipe-away dismissal cannot fire the
    // action by accident.
    LaunchedEffect(notification) {
        val undo = notification as? Notification.Undo ?: return@LaunchedEffect
        notification = null
        val result = snackbarHostState.showSnackbar(
            message = undo.title,
            actionLabel = undo.actionLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) undo.onAction()
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(hostState = snackbarHostState)
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

        // The undo toast is presented by the LaunchedEffect above — that is where the
        // host state lives. This branch exists only to keep `when` exhaustive.
        is Notification.Undo -> Unit

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
