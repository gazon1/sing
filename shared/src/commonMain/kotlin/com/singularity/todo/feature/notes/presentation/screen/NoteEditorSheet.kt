package com.singularity.todo.feature.notes.presentation.screen

/**
 * Discriminates the currently shown overlays in [NoteEditorScreenContent].
 *
 * The editor shows one overlay at a time, and they are not all link entries:
 * [External] shows the URL-entry dialog, [InternalPicker] the search sheet for
 * an internal link, and [AiAction] the AI action sheet.
 */
sealed class NoteEditorSheet {
    data object External : NoteEditorSheet()

    data class InternalPicker(val query: String = "") : NoteEditorSheet()

    data object AiAction : NoteEditorSheet()
}
