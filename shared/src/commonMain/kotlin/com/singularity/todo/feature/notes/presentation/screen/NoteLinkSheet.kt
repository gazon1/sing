package com.singularity.todo.feature.notes.presentation.screen

/**
 * Discriminates the currently shown overlays in [NoteEditorScreenContent].
 * [External] shows the URL-entry dialog. [InternalPicker] shows the search sheet.
 * [AiAction] shows the AI action sheet.
 */
sealed class NoteLinkSheet {
    data object External : NoteLinkSheet()

    data class InternalPicker(val query: String = "") : NoteLinkSheet()

    data object AiAction : NoteLinkSheet()
}
