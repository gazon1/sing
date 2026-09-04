package com.singularity.todo.feature.notes

/**
 * Pure formatter for note-related AI results. Kept out of the Composable so it
 * can be unit-tested without launching a Compose runtime.
 */
internal fun formatNoteAiResult(result: NoteAiResult): String = when (result) {
    is NoteAiResult.Improved -> "Note improved!\n\nTitle: ${result.title}"
    is NoteAiResult.Error -> "Error: ${result.message}"
}
