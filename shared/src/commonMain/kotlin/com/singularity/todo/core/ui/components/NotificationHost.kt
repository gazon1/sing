package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
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
 *
 * ## Why the undo toast is a Popup
 *
 * The snackbar used to be an in-window sibling of the screen content, which
 * put it behind two other layers at once: the content's opaque background
 * (the host was composed before it) and the shell-level FAB (drawn after the
 * whole entry). The toast was laid out and timed correctly but never visible
 * — and its action label sat under the FAB even when it was. Rendering the
 * snackbar in its own [Popup] window, anchored to the host's bounds, puts it
 * above both. Dialog notifications were already separate windows.
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

    // Present the undo toast. `showSnackbar` suspends until the toast is
    // dismissed, so one effect both presents it and keeps the next event from
    // stacking behind it. Taking the result here — rather than from a button
    // callback — means a swipe-away dismissal cannot fire the action by
    // accident.
    //
    // The queue is cleared only AFTER the snackbar finishes: this effect is
    // keyed on `notification`, so nulling it up front would change the key and
    // cancel `showSnackbar` on the very next frame — the toast never reaches
    // the screen (regression covered by Maestro tasks/06-delete-undo).
    LaunchedEffect(notification) {
        val undo = notification as? Notification.Undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = undo.title,
            actionLabel = undo.actionLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) undo.onAction()
        notification = null
    }

    // The undo toast lives in its own window: composed as an in-window sibling it
    // lost the z-order fight twice — the content's opaque background is drawn after
    // it, and the shell FAB is drawn around the whole entry. It was laid out and
    // timed correctly but never visible, so a flow could assert on a node that
    // existed in the semantics tree while being painted under two opaque layers.
    //
    // A Popup has its own semantics root, so the app-root testTagsAsResourceId does
    // not reach inside it — the tagged host re-asserts the mapping, and without it
    // the action button is unaddressable by UI automation. That is also why the
    // action is selected by id rather than by its label: this project's device runs
    // in Russian, and `text: "Undo"` can never match.
    Box(modifier = modifier.fillMaxSize()) {
        Popup(
            popupPositionProvider = BottomCenterAnchorPositionProvider(),
            properties = PopupProperties(focusable = false),
        ) {
            TaggedSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.mapTestTagsAsResourceIds(),
            )
        }
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

/**
 * Positions the popup bottom-center of its anchor (the host's full-screen
 * Box) — the same place the in-window snackbar used to sit, above the shell
 * bottom bar but inside the nav-entry area.
 */
private class BottomCenterAnchorPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2,
        y = anchorBounds.bottom - popupContentSize.height,
    )
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
