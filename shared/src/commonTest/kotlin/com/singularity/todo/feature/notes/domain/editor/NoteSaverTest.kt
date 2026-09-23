package com.singularity.todo.feature.notes.domain.editor

import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteSaverTest {

    private val userId = UserId("test-user")
    private val noteId = NoteId("note-1")
    private val testNote = Note(
        id = noteId,
        userId = userId,
        title = "Existing Note",
        bodyHtml = "<p>Old content</p>",
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
    )

    private val log = Logger.withTag("NoteSaverTest")

    private fun makeSavers(repo: FakeNotesRepository): Pair<NoteSaver, Channel<NotesUiEvent>> {
        val events = Channel<NotesUiEvent>(Channel.BUFFERED)
        val savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val saver = NoteSaver(repo, log, events, savedPulse)
        return saver to events
    }

    @Test
    fun `save calls updateContent when isNew is false`() = runTest {
        val repo = FakeNotesRepository()
        repo.seed(testNote)
        val (saver, _) = makeSavers(repo)

        val result = saver.save(noteId, "New Title", "<p>New content</p>", isNew = false)

        assertTrue(result.isSuccess)
        val updated = repo.notes[noteId.value]
        assertEquals("New Title", updated?.title)
        assertEquals("<p>New content</p>", updated?.bodyHtml)
    }

    @Test
    fun `save calls createWithContent when isNew is true`() = runTest {
        val repo = FakeNotesRepository()
        val (saver, _) = makeSavers(repo)

        val result = saver.save(NoteId("new-note"), "New Note", "<p>Brand new</p>", isNew = true)

        assertTrue(result.isSuccess)
        assertTrue(repo.notes.containsKey("new-note"))
        assertEquals("New Note", repo.notes["new-note"]?.title)
    }

    @Test
    fun `save emits SavedPulse on success`() = runTest {
        val repo = FakeNotesRepository()
        repo.seed(testNote)
        val savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val events = Channel<NotesUiEvent>(Channel.BUFFERED)
        val saver = NoteSaver(repo, log, events, savedPulse)

        val received = mutableListOf<Unit>()
        val job = launch { savedPulse.take(1).collect { received += it } }
        runCurrent() // ensure collector is subscribed before emit

        val result = saver.save(noteId, "Title", "<p>Body</p>", isNew = false)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(listOf(Unit), received)
    }

    @Test
    fun `save returns failure when updateContent fails`() = runTest {
        val repo = FakeNotesRepository()
        repo.seed(testNote)
        repo.updateContentOverride = Result.failure(RuntimeException("DB write failed"))
        val savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val events = Channel<NotesUiEvent>(Channel.BUFFERED)
        val saver = NoteSaver(repo, log, events, savedPulse)

        val result = saver.save(noteId, "Title", "<p>Body</p>", isNew = false)

        assertTrue(result.isFailure)
        assertEquals("DB write failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `save isNew does not affect existing note`() = runTest {
        val repo = FakeNotesRepository()
        repo.seed(testNote)
        val (saver, _) = makeSavers(repo)

        // With isNew=true and a different id, creates a NEW entry
        saver.save(NoteId("brand-new"), "Brand New", "<p>Content</p>", isNew = true)

        // Original note should be unchanged
        assertEquals("Existing Note", repo.notes[testNote.id.value]?.title)
    }
}
