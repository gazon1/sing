package com.singularity.todo.feature.notes

import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock

// Keep: has domain timestamp + creates Note with generated ID
class CreateNoteUseCase(private val repo: NotesRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateNoteInput): Result<NoteId> = runCatchingResult {
        val now = clock.now()
        val note = Note(
            id = NoteId.generate(),
            userId = input.userId,
            title = input.title,
            bodyMarkdown = input.bodyMarkdown,
            isFolder = input.isFolder,
            parentNoteId = input.parentNoteId,
            createdAt = now,
            updatedAt = now
        )
        repo.create(note).getOrThrow()
        note.id
    }
}

// Keep: has domain timestamp update
class UpdateNoteUseCase(private val repo: NotesRepository, private val clock: Clock) {
    suspend operator fun invoke(note: Note): Result<Unit> = runCatchingResult {
        repo.update(note.copy(updatedAt = clock.now())).getOrThrow()
    }
}
