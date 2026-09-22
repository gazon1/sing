package com.singularity.todo.feature.notes.domain.editor

import com.singularity.todo.feature.notes.EditorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the editable state for [com.singularity.todo.feature.notes.presentation.viewmodel.NoteEditor].
 * Centralises dirty/new tracking so the VM doesn't sprinkle `current.copy(...)` everywhere.
 * Follows the [com.singularity.todo.feature.projects.domain.ProjectDetailDraftState] pattern.
 */
internal class NoteEditorState {
    private val _state = MutableStateFlow<EditorState>(EditorState.Empty)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** The current editing session, or null if the editor is empty. */
    val current: EditorState.Editing? get() = _state.value as? EditorState.Editing

    fun open(editing: EditorState.Editing) {
        _state.value = editing
    }

    fun clear() {
        _state.value = EditorState.Empty
    }

    fun updateTitle(title: String) {
        val c = current ?: return
        _state.value = c.copy(title = title, isDirty = true)
    }

    fun updateHtml(html: String) {
        val c = current ?: return
        _state.value = c.copy(html = html, isDirty = true)
    }

    fun applyImprove(newTitle: String, newHtml: String) {
        val c = current ?: return
        _state.value = c.copy(title = newTitle, html = newHtml, isDirty = true)
    }

    /**
     * Clears the dirty flag after a successful persist.
     * Also clears the `isNew` flag so subsequent saves go through [NotesRepository.updateContent]
     * instead of [NotesRepository.createWithContent].
     */
    fun markSaved() {
        val c = current ?: return
        _state.value = c.copy(isDirty = false, isNew = false)
    }
}
