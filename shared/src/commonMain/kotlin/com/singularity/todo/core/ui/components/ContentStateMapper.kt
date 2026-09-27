package com.singularity.todo.core.ui.components

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.tags.TagsUiState
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsUiState

/**
 * Shared `toContentState()` helpers for sealed UI states that follow the
 * `Loading / Empty / Error / Content(val T)` pattern.
 *
 * Each screen provides a thin local extension (2 lines) that delegates here.
 * Keeping the mapping logic here once avoids copy-paste errors when a new
 * variant is added to the sealed interface.
 *
 * ## Usage
 *
 * In your screen file:
 * ```kotlin
 * private fun NotesUiState.toContentState() =
 *     ContentStateMapper.notes(this) { notes }
 *
 * private fun TagsUiState.toContentState() =
 *     ContentStateMapper.tags(this) { tags }
 * ```
 */
object ContentStateMapper {

    fun notes(state: NotesUiState): ContentState<List<com.singularity.todo.feature.notes.Note>> =
        when (state) {
            is NotesUiState.Loading -> ContentState.Loading
            is NotesUiState.Empty -> ContentState.Empty
            is NotesUiState.Error -> ContentState.Error(AppError.Unknown(state.message))
            is NotesUiState.Content -> ContentState.Ready(state.list.pinned + state.list.unpinned)
        }

    fun tags(state: TagsUiState): ContentState<List<com.singularity.todo.feature.tags.Tag>> =
        when (state) {
            is TagsUiState.Loading -> ContentState.Loading
            is TagsUiState.Empty -> ContentState.Empty
            is TagsUiState.Error -> ContentState.Error(AppError.Unknown(state.message))
            is TagsUiState.Content -> ContentState.Ready(state.tags)
        }

    fun tagGroups(
        state: TagGroupsUiState,
    ): ContentState<List<com.singularity.todo.feature.tags.domain.model.TagGroup>> =
        when (state) {
            is TagGroupsUiState.Loading -> ContentState.Loading
            is TagGroupsUiState.Empty -> ContentState.Empty
            is TagGroupsUiState.Error -> ContentState.Error(AppError.Unknown(state.message))
            is TagGroupsUiState.Content -> ContentState.Ready(state.groups)
        }
}
