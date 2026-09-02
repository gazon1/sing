package com.singularity.todo.feature.notes

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow

class GetNotesUseCase(private val repo: NotesRepository) {
    operator fun invoke(userId: UserId): Flow<List<Note>> = repo.watchNotes(userId)
}

class GetNoteUseCase(private val repo: NotesRepository) {
    operator fun invoke(id: NoteId): Flow<Note?> = repo.watchNote(id)
}

class SearchNotesUseCase(private val repo: NotesRepository) {
    operator fun invoke(query: String): Flow<List<Note>> = repo.searchNotes(query)
}

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

class UpdateNoteUseCase(private val repo: NotesRepository, private val clock: Clock) {
    suspend operator fun invoke(note: Note): Result<Unit> = runCatchingResult {
        repo.update(note.copy(updatedAt = clock.now())).getOrThrow()
    }
}

class DeleteNoteUseCase(private val repo: NotesRepository) {
    suspend operator fun invoke(id: NoteId): Result<Unit> = runCatchingResult {
        repo.softDelete(id).getOrThrow()
    }
}
