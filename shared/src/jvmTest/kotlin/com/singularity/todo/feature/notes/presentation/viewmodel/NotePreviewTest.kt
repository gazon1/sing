@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class NotePreviewTest {

    private val testUserId = UserId("test-user")

    private val testNote = Note(
        id = NoteId("note-1"),
        userId = testUserId,
        title = "Test Note",
        bodyHtml = "<p>Hello world</p>",
        wordCount = 2,
        charCount = 11,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private val emptyLinkRepo = object : InternalLinkRepository {
        override suspend fun searchNotes(query: String): List<Note> = emptyList()
        override suspend fun searchTasks(query: String): List<Task> = emptyList()
        override suspend fun getBacklinkNotes(noteId: String): List<Note> = emptyList()
        override suspend fun getBacklinkTasks(taskId: String): List<Task> = emptyList()
        override suspend fun getNotesLinkingToTask(taskId: String): List<Note> = emptyList()
    }

    private fun createVm(notesRepo: FakeNotesRepository = FakeNotesRepository(), scope: CoroutineScope): NotePreview =
        NotePreview(
            repo = notesRepo,
            linkRepo = emptyLinkRepo,
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )

    @Test
    fun initial_state_is_Loading() = runTest {
        val vm = createVm(scope = this)
        assertIs<NotePreviewState.Loading>(vm.state.value)
    }

    @Test
    fun Load_sets_state_to_Loaded() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.onIntent(NotePreviewIntent.Load(testNote.id.value))
        advanceTimeBy(1_000)
        runCurrent()

        val state = vm.state.value
        assertIs<NotePreviewState.Loaded>(state)
        assertEquals(testNote.id, state.note.id)
        assertEquals("Test Note", state.note.title)
    }

    @Test
    fun Refresh_reloads_current_note() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.onIntent(NotePreviewIntent.Load(testNote.id.value))
        advanceTimeBy(1_000)
        runCurrent()

        // Update the note in the store — collect{} sees the new emission
        notesRepo.add(testNote.copy(title = "Updated Title"))

        vm.onIntent(NotePreviewIntent.Refresh)
        advanceTimeBy(1_000)
        runCurrent()

        val state = vm.state.value
        assertIs<NotePreviewState.Loaded>(state)
        assertEquals("Updated Title", state.note.title)
    }

    @Test
    fun rapid_Load_calls_cancel_previous_job() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        // Start two loads in rapid succession — second should cancel first
        vm.onIntent(NotePreviewIntent.Load(testNote.id.value))
        vm.onIntent(NotePreviewIntent.Load(testNote.id.value))
        advanceTimeBy(1_000)
        runCurrent()

        // Should not throw — second load cancelled the first
        assertIs<NotePreviewState.Loaded>(vm.state.value)
    }

    @Test
    fun Delete_fires_softDelete_on_repository() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.onIntent(NotePreviewIntent.Load(testNote.id.value))
        advanceTimeBy(1_000)
        runCurrent()

        vm.onIntent(NotePreviewIntent.Delete)
        advanceTimeBy(1_000)
        runCurrent()

        // Note should be soft-deleted (deletedAt set)
        val notes = notesRepo.notes
        assertEquals(1, notes.size)
        assertNotNull(notes.values.first().deletedAt)
    }

    @Test
    fun Delete_when_not_loaded_does_nothing() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.onIntent(NotePreviewIntent.Delete)
        advanceTimeBy(1_000)
        runCurrent()

        // No error, state unchanged
        assertIs<NotePreviewState.Loading>(vm.state.value)
    }
}
