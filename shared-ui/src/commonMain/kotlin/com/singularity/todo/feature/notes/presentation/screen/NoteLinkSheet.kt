package com.singularity.todo.feature.notes.presentation.screen

import kotlinx.serialization.Serializable

/**
 * Discriminates the currently shown link-entry overlay in [NoteEditorScreenContent].
 * [External] shows the URL-entry dialog. [InternalPicker] shows the search sheet.
 *
 * `@Serializable` because the open sheet is saved across a rotation and process
 * death by [com.singularity.todo.core.ui.components.rememberOverlayState].
 */
@Serializable
sealed class NoteLinkSheet {
    @Serializable
    data object External : NoteLinkSheet()

    @Serializable
    data class InternalPicker(val query: String = "") : NoteLinkSheet()
}
