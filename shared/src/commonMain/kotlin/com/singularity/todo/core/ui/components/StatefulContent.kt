package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
 *     content = { tasks -> TasksList(tasks) }
 * )
 * ```
 *
 * @param state         The mapped [ContentState].
 * @param emptyTitle    Title shown when state is [ContentState.Empty].
 * @param emptySubtitle Optional subtitle for the empty state.
 * @param modifier      Standard Compose modifier.
 * @param content       The "ready" content lambda — receives the typed value.
 */
@Composable
fun <T> StatefulContent(
    state: ContentState<T>,
    emptyTitle: String,
    emptySubtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
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
            )
        }
        is ContentState.Error -> {
            EmptyState(
                title = "Error: ${state.message}",
                modifier = modifier,
            )
        }
        is ContentState.Ready -> {
            content(state.value)
        }
    }
}

/**
 * Canonical sealed set of content-bearing UI states.
 * Used by [StatefulContent] to route to the appropriate renderer.
 */
sealed interface ContentState<out T> {
    data object Loading : ContentState<Nothing>
    data object Empty : ContentState<Nothing>
    data class Error(val message: String) : ContentState<Nothing>
    data class Ready<T>(val value: T) : ContentState<T>
}
