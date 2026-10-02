package com.singularity.todo.feature.notes.presentation.screen

/**
 * Discriminates the currently shown link-entry overlay in [NoteEditorScreenContent].
 * [External] shows the URL-entry dialog. [InternalPicker] shows the search sheet.
 */
sealed class NoteLinkSheet {
    data object External : NoteLinkSheet()
    data class InternalPicker(val query: String = "") : NoteLinkSheet()
}
