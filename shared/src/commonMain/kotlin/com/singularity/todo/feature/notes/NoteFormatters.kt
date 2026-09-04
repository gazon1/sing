package com.singularity.todo.feature.notes

/**
 * Pure formatters for notes — testable without Compose runtime.
 */
internal fun formatNoteAiResult(result: NoteAiResult): String = when (result) {
    is NoteAiResult.Improved -> "Note improved!\n\nTitle: ${result.title}"
    is NoteAiResult.Error -> "Error: ${result.message}"
}

internal fun canSaveNote(state: EditorState): Boolean =
    state is EditorState.Editing && state.title.isNotBlank()
