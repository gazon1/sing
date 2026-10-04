package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick

/**
 * Unified content renderer for sealed UI states that follow the
 * Loading → Empty / Error → Ready pattern.
 *
 * Replaces the `when (state) { Loading → LoadingIndicator(); Empty → EmptyState(...); Error → EmptyState("Error: $msg"); Ready(value) → content(value) }`
 * ceremony that was copy-pasted across 7 screens.
 *
 * ## Usage
 *
 * Each screen provides a thin local extension that delegates to [ContentStateMapper]:
 * ```kotlin
 * private fun NotesUiState.toContentState() =
 *     ContentStateMapper.contentStateOf(this) { it.notes }
 * ```
 *
 * The screen then simply:
 * ```kotlin
 * StatefulContent(
 *     state = state.toContentState(),
 *     emptyTitle = "No tasks yet",
 *     emptyActions = {
 *         FilledTonalButton(onClick = { onIntent(TasksIntent.CreateNew) }) {
 *             Text("Create task")
 *         }
 *     },
 *     onRetry = { onIntent(TasksIntent.Reload) },
 *     content = { tasks, contentModifier -> TasksList(tasks, contentModifier) },
 * )
 * ```
 *
 * @param state            The mapped [ContentState]. **UI-only type** — build it in the
 *   screen via a `ContentStateMapper` extension, never return it from a ViewModel;
 *   VMs expose their own domain `UiState` (see `DraftUiState` and friends).
 * @param emptyTitle       Title shown when state is [ContentState.Empty].
 * @param emptySubtitle    Optional subtitle for the empty state.
 * @param emptyActions     Optional [EmptyStateActions] slot forwarded as-is to
 *   [EmptyState]'s `actions` parameter — e.g. a CTA button such as
 *   `{ FilledTonalButton(onClick = ...) { Text("Create task") } }`. `null` (the
 *   default) renders no action, matching [EmptyState]'s own default.
 * @param errorTitle       Optional override for the error state's title. Defaults to
 *   a generic "Something went wrong" — the underlying [AppError] message is shown
 *   as the subtitle, so callers don't have to build a display string themselves.
 * @param onRetry          Invoked when the error state's retry button is tapped.
 *   Omit to hide the retry button (e.g. for non-recoverable errors).
 * @param modifier         Standard Compose modifier, applied to whichever branch
 *   renders — including [content], so `Ready` shares the same layout slot as
 *   `Loading`/`Empty`/`Error` instead of silently dropping it.
 * @param content          The "ready" content lambda — receives the typed value and
 *   the same [modifier] passed to this composable.
 */
@Composable
fun <T> StatefulContent(
    state: ContentState<T>,
    emptyTitle: String,
    emptySubtitle: String? = null,
    emptyActions: EmptyStateActions? = null,
    errorTitle: String = "Something went wrong",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (value: T, modifier: Modifier) -> Unit,
) {
    when (state) {
        is ContentState.Loading -> {
            LoadingIndicator(modifier = modifier)
        }

        is ContentState.Empty -> {
            EmptyState(
                title = emptyTitle,
                subtitle = emptySubtitle,
                modifier = modifier,
                actions = emptyActions,
            )
        }

        is ContentState.Error -> {
            ErrorState(
                title = errorTitle,
                error = state.error,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is ContentState.Ready -> {
            content(state.value, modifier)
        }
    }
}

/**
 * Dedicated error rendering — distinct from [EmptyState] both semantically
 * (an error is not "nothing here", it's "something failed") and visually
 * (error color, retry affordance). See [StatefulContent] review point 2:
 * reusing `EmptyState(title = "Error: $msg")` gave errors no styling, no
 * retry action, and baked an unlocalized "Error: " prefix into a shared
 * component.
 */
@Composable
private fun ErrorState(title: String, error: AppError, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    // Mirrors EmptyState's centered Box+Column layout so Loading/Empty/Error
    // all occupy their slot the same way; title uses the error color so it
    // doesn't read as just another empty-state message.
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(
                text = error.toMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRetry != null) {
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/**
 * Canonical sealed set of content-bearing UI states.
 * Used by [StatefulContent] to route to the appropriate renderer.
 *
 * **UI-only type.** This lives in `core.ui` and exists purely to drive
 * [StatefulContent]'s rendering branch. ViewModels must not expose it
 * directly — a VM exposes its own domain state (e.g. `DraftUiState`), and a
 * screen-local `ContentStateMapper` extension projects that into a
 * `ContentState` right before rendering. If `ContentState` starts appearing
 * in VM signatures, the projection has leaked and screens will start
 * disagreeing on how to map the same domain state (e.g. `isSaving` into
 * `Loading` vs `Ready`).
 */
sealed interface ContentState<out T> {
    data object Loading : ContentState<Nothing>
    data object Empty : ContentState<Nothing>

    /**
     * @param error The typed domain error, not a pre-rendered string — so the
     *   UI layer can style/localize/retry differently per error kind instead
     *   of losing that information at the `ContentState` boundary.
     */
    data class Error(val error: AppError) : ContentState<Nothing>
    data class Ready<out T>(val value: T) : ContentState<T>
}

@Preview
@Composable
private fun StatefulContentLoadingPreview() = PreviewThemed(darkTheme = false) {
    StatefulContent<String>(
        state = ContentState.Loading,
        emptyTitle = "No items",
    ) { value, modifier -> Text(value, modifier) }
}

@Preview
@Composable
private fun StatefulContentEmptyPreview() = PreviewThemed(darkTheme = false) {
    StatefulContent<String>(
        state = ContentState.Empty,
        emptyTitle = "No tasks yet",
        emptySubtitle = "Create your first task",
        emptyActions = {
            FilledTonalButton(onClick = noopClick) { Text("Create task") }
        },
    ) { value, modifier -> Text(value, modifier) }
}

@Preview
@Composable
private fun StatefulContentErrorPreview() = PreviewThemed(darkTheme = true) {
    StatefulContent<String>(
        state = ContentState.Error(AppError.Network("Timed out", code = "content.state.timeout")),
        emptyTitle = "No tasks",
        onRetry = noopClick,
    ) { value, modifier -> Text(value, modifier) }
}

@Preview
@Composable
private fun StatefulContentReadyPreview() = PreviewThemed(darkTheme = false) {
    StatefulContent(
        state = ContentState.Ready("Sample task content"),
        emptyTitle = "No tasks",
    ) { value, modifier ->
        Box(modifier = modifier.fillMaxSize()) {
            Text(text = value)
        }
    }
}
