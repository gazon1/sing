package com.singularity.todo.feature.notes.domain

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository

/**
 * Pure logic for selecting and applying a note template.
 */
class TemplatePicker(
    private val notesRepository: NotesRepository,
) {
    /**
     * Returns all available templates, ordered by title.
     */
    fun templates(): kotlinx.coroutines.flow.Flow<List<Note>> = notesRepository.watchTemplates()

    /**
     * Applies [templateId] to create a new note titled [targetTitle],
     * optionally anchored to [targetDateKey] (sets kind to Daily).
     *
     * Returns the new note id, or an error if the template does not exist.
     */
    suspend fun apply(templateId: NoteId, targetTitle: String, targetDateKey: String?): Result<NoteId> =
        notesRepository.createFromTemplate(templateId, targetTitle, targetDateKey)

    /**
     * Marks an existing note as a template (changes kind to Template).
     */
    suspend fun saveAsTemplate(id: NoteId): Result<Unit> = notesRepository.saveAsTemplate(id)

    /**
     * Returns true if [note] is already a template.
     */
    fun isTemplate(note: Note): Boolean = note.kind == com.singularity.todo.feature.notes.NoteKind.Template
}
